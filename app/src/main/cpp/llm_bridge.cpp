#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"

#define TAG "QwenLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 持有一个模型 + 上下文的句柄
struct LlmHandle {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    int            n_ctx = 0;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_idea2realclaw_qwenllm_LlamaWrapper_loadModel(
        JNIEnv* env, jobject, jstring jpath, jint jnGpuLayers, jint jnCtx) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = jnGpuLayers;            // 99 = 全部层卸载到 GPU(Vulkan)
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("loadModel: failed to load %s", path); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = jnCtx;
    cparams.n_threads = 4;
    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) { llama_model_free(model); LOGE("loadModel: failed to create context"); return 0; }

    auto* h = new LlmHandle{model, ctx, jnCtx};
    LOGI("loadModel: ok, n_ctx=%d, gpu_layers=%d", jnCtx, jnGpuLayers);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_idea2realclaw_qwenllm_LlamaWrapper_generate(
        JNIEnv* env, jobject, jlong jhandle, jstring jprompt, jobject jcallback) {

    auto* h = reinterpret_cast<LlmHandle*>(jhandle);
    if (!h || !h->model || !h->ctx) { LOGE("generate: invalid handle"); return; }

    // master API: tokenize / piece / eog 都需要 llama_vocab*
    const llama_vocab * vocab = llama_model_get_vocab(h->model);

    const char* p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    // Qwen 系列使用 ChatML 模板
    std::string full = "<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n";

    // tokenize（master API: 需 vocab + 预分配数组；n_tok<0 表示 buffer 不足，按绝对值扩容）
    std::vector<llama_token> inp(full.size() * 2 + 16, 0);
    int32_t n_tok = llama_tokenize(vocab, full.c_str(), (int32_t)full.size(),
                                   inp.data(), (int32_t)inp.size(), true, true);
    if (n_tok < 0) {
        inp.resize((size_t)(-n_tok) + 1);
        n_tok = llama_tokenize(vocab, full.c_str(), (int32_t)full.size(),
                               inp.data(), (int32_t)inp.size(), true, true);
    }
    if (n_tok <= 0) { LOGE("generate: tokenize failed"); return; }
    inp.resize((size_t)n_tok);
    if (inp.empty()) { LOGE("generate: empty prompt tokens"); return; }

    const int n_prompt = (int)inp.size();
    // master API: llama_batch_add/clear 已移除，需手动填充结构体。
    // 注意：不能覆盖 seq_id[i] 指针（由 llama_batch_init 分配），否则 llama_batch_free 会崩溃，
    // 只能写 seq_id[i][0] 的内容。
    llama_batch batch = llama_batch_init(n_prompt, 0, 1);
    for (int i = 0; i < n_prompt; i++) {
        batch.token[i]     = inp[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == n_prompt - 1) ? 1 : 0;  // 仅末 token 需 logits
    }
    batch.n_tokens = n_prompt;

    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("generate: first decode failed");
        llama_batch_free(batch);
        return;
    }

    // 采样链：temperature / top_p / 随机种子
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    // 回调方法（定义在 TokenCallback 接口上）
    jclass cbClass = env->FindClass("com/idea2realclaw/qwenllm/TokenCallback");
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onDone  = env->GetMethodID(cbClass, "onDone", "()V");

    int n_cur = n_prompt;
    int n_decode = 0;
    const int n_max = 2048;

    while (n_decode < n_max) {
        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        // token -> piece（master API: 需 vocab + 显式 buffer，不写 null 终止符，需按长度构造）
        std::vector<char> buf(256);
        int32_t n_piece = llama_token_to_piece(vocab, id, buf.data(), (int32_t)buf.size(), 0, false);
        if (n_piece < 0) {
            buf.resize((size_t)(-n_piece) + 1);
            n_piece = llama_token_to_piece(vocab, id, buf.data(), (int32_t)buf.size(), 0, false);
        }
        std::string piece;
        if (n_piece > 0) piece.assign(buf.data(), (size_t)n_piece);

        if (!piece.empty()) {
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(jcallback, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        // 单 token 解码：复用 batch buffer（仅填 index 0，不覆盖 seq_id 指针）
        batch.token[0]     = id;
        batch.pos[0]       = n_cur;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;
        batch.n_tokens     = 1;
        if (llama_decode(h->ctx, batch) != 0) { LOGE("generate: decode failed at %d", n_decode); break; }
        n_cur++; n_decode++;
    }

    if (onDone) env->CallVoidMethod(jcallback, onDone);

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    LOGI("generate: done, tokens=%d", n_decode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_idea2realclaw_qwenllm_LlamaWrapper_freeModel(JNIEnv*, jobject, jlong jhandle) {
    auto* h = reinterpret_cast<LlmHandle*>(jhandle);
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
    LOGI("freeModel: released");
}
