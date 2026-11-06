#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <chrono>
#include <android/log.h>

#include "llama.h"

#define TAG "QwenLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 持有一个模型 + 上下文的句柄。
// cancelled 由 UI 线程置位、由生成线程（Dispatchers.IO）轮询，用于中断 generate。
struct LlmHandle {
    llama_model*      model     = nullptr;
    llama_context*    ctx       = nullptr;
    int               n_ctx     = 0;
    std::atomic<bool> cancelled{false};
};

static double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - t0).count();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_idea2realclaw_qwenllm_LlamaWrapper_loadModel(
        JNIEnv* env, jobject, jstring jpath, jint jnGpuLayers, jint jnCtx) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = jnGpuLayers;            // 99 = 全部层卸载到 GPU(Vulkan)
    llama_model* model = llama_model_load_from_file(path, mparams);
    if (!model) { LOGE("loadModel: failed to load %s", path); }
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = jnCtx;
    cparams.n_threads = 4;
    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) { llama_model_free(model); LOGE("loadModel: failed to create context"); return 0; }

    // std::atomic 不可拷贝，不能用聚合初始化，必须逐个赋值。
    auto* h = new LlmHandle();
    h->model = model;
    h->ctx   = ctx;
    h->n_ctx = jnCtx;
    LOGI("loadModel: ok, n_ctx=%d, gpu_layers=%d", jnCtx, jnGpuLayers);
    return reinterpret_cast<jlong>(h);
}

/**
 * fullPrompt 由 Kotlin 侧按 ChatML 拼好的**完整对话**：
 *   <|im_start|>system\n...<|im_end|>\n
 *   <|im_start|>user\n...<|im_end|>\n
 *   <|im_start|>assistant\n...<|im_end|>\n
 *   <|im_start|>assistant\n        <- 生成起点，末尾必须有这一行
 *
 * 这样 system prompt / 多轮历史 / 工具结果回灌都由 Kotlin 控制，C++ 不依赖 JSON 解析器。
 * 每次调用清空 KV cache 后全量 prefill：Agent 循环的每一轮 wire 都会变（追加工具结果），
 * 增量 KV 续接的正确性差、实现复杂，端侧 3B 模型优先保证语义正确。
 * 代价是每轮重放历史，真实耗时由 prefill 日志给出（logcat tag: QwenLlmJni）。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_idea2realclaw_qwenllm_LlamaWrapper_generate(
        JNIEnv* env, jobject, jlong jhandle, jstring jfullPrompt, jobject jcallback) {

    auto* h = reinterpret_cast<LlmHandle*>(jhandle);
    if (!h || !h->model || !h->ctx) { LOGE("generate: invalid handle"); return; }
    h->cancelled.store(false, std::memory_order_relaxed);

    const llama_vocab * vocab = llama_model_get_vocab(h->model);

    const char* p = env->GetStringUTFChars(jfullPrompt, nullptr);
    std::string full(p ? p : "");
    env->ReleaseStringUTFChars(jfullPrompt, p);
    if (full.empty()) { LOGE("generate: empty prompt"); return; }

    // parse_special=true 才能把 <|im_start|> / <|im_end|> 识别成特殊 token。
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

    const int n_prompt = (int)inp.size();
    if (n_prompt + 8 >= h->n_ctx) {
        LOGE("generate: prompt %d tokens exceeds n_ctx %d", n_prompt, h->n_ctx);
        return;
    }

    // 全量重放前清 KV cache，避免上一轮残留污染。
    llama_memory_t mem = llama_get_memory(h->ctx);
    if (mem) llama_memory_clear(mem, true);

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

    auto t_prefill = std::chrono::steady_clock::now();
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("generate: first decode failed");
        llama_batch_free(batch);
        return;
    }
    const double prefill_ms = ms_since(t_prefill);
    LOGI("prefill: %d tokens in %.2f ms", n_prompt, prefill_ms);

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
    bool stopped = false;
    auto t_decode = std::chrono::steady_clock::now();

    while (n_decode < n_max) {
        if (h->cancelled.load(std::memory_order_relaxed)) { stopped = true; break; }
        if (n_cur >= h->n_ctx - 2) { LOGI("generate: context full at pos %d", n_cur); break; }

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

    const double decode_ms = ms_since(t_decode);
    const double tps = decode_ms > 0.0 ? (n_decode * 1000.0 / decode_ms) : 0.0;
    LOGI("decode: %d tokens in %.2f ms (%.2f t/s), stopped=%d, prefill_ms=%.2f",
         n_decode, decode_ms, tps, stopped ? 1 : 0, prefill_ms);

    if (onDone) env->CallVoidMethod(jcallback, onDone);

    llama_sampler_free(smpl);
    llama_batch_free(batch);
}

/** UI 线程调用：置取消标志，生成线程在下一个 token 边界退出。 */
extern "C" JNIEXPORT void JNICALL
Java_com_idea2realclaw_qwenllm_LlamaWrapper_stopGenerate(JNIEnv*, jobject, jlong jhandle) {
    auto* h = reinterpret_cast<LlmHandle*>(jhandle);
    if (!h) return;
    h->cancelled.store(true, std::memory_order_relaxed);
    LOGI("stopGenerate: cancel flag set");
}

extern "C" JNIEXPORT void JNICALL
Java_com_idea2realclaw_qwenllm_LlamaWrapper_freeModel(JNIEnv*, jobject, jlong jhandle) {
    auto* h = reinterpret_cast<LlmHandle*>(jhandle);
    if (!h) return;
    // 调用方（ViewModel.onCleared）需先 stopGenerate 并等待生成协程退出；
    // 这里再置一次标志，避免释放后仍有循环在跑。
    h->cancelled.store(true, std::memory_order_relaxed);
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
    LOGI("freeModel: released");
}
