import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM regression for ModelDownloader: runs the *real* Kotlin .class produced by
 * assembleDebug. No Android device, no 2 GB network transfer, no mock reimplementation.
 *
 *  - parseTotal(): Content-Range parsing and fallbacks
 *  - isValid(): GGUF magic + minimum size guard
 *  - fetch(): real HTTP 206 resume against a local server, completion check,
 *             atomic rename, cancel semantics (.part kept)
 *
 * The server exposes a 1 GiB + 4 B logical GGUF file but only 1000 bytes are
 * transferred (we pre-create a sparse .part), so the test stays fast.
 */
public class ModelDownloaderSelfTest {

    static int pass = 0;
    static int fail = 0;
    static final long GIB = 1024L * 1024 * 1024;
    static final long TOTAL = GIB + 4;   // must clear ModelDownloader.MIN_VALID_BYTES

    static void check(String label, boolean ok, String extra) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + label
                + (extra.isEmpty() ? "" : " :: " + extra));
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.idea2realclaw.qwenllm.ModelDownloader");
        Object inst = c.getField("INSTANCE").get(null);
        Method parseTotal = c.getDeclaredMethod(
                "parseTotal", String.class, long.class, long.class, int.class);
        parseTotal.setAccessible(true);
        Method isValid = c.getMethod("isValid", File.class);
        Method fetch = c.getDeclaredMethod("fetch",
                String.class, File.class, File.class, int.class,
                Function1.class, Function0.class);
        fetch.setAccessible(true);

        File tmp = Files.createTempDirectory("localwiki-dl-test").toFile();
        HttpServer server = null;
        try {
            // ---- 1. parseTotal ----
            long t1 = ((Long) parseTotal.invoke(inst,
                    "bytes 0-15/2104932768", 0L, 16L, 206)).longValue();
            check("Content-Range gives full length", t1 == 2104932768L, t1 + " B");

            long t2 = ((Long) parseTotal.invoke(inst, null, 100L, 50L, 206)).longValue();
            check("206 fallback = resumeAt + Content-Length", t2 == 150L, t2 + " B");

            long t3 = ((Long) parseTotal.invoke(inst, null, 0L, 2104932768L, 200)).longValue();
            check("200 uses Content-Length", t3 == 2104932768L, t3 + " B");

            long t4 = ((Long) parseTotal.invoke(inst, null, 0L, -1L, 200)).longValue();
            check("unknown length -> -1", t4 == -1L, String.valueOf(t4));

            // ---- 2. isValid ----
            File small = new File(tmp, "small.gguf");
            try (RandomAccessFile r = new RandomAccessFile(small, "rw")) {
                r.write("GGUF".getBytes(StandardCharsets.US_ASCII));
            }
            check("tiny file rejected", !((Boolean) isValid.invoke(inst, small)),
                    small.length() + " B");

            File big = sparseGguf(new File(tmp, "big.gguf"), TOTAL, "GGUF");
            check("1GiB+4 GGUF accepted", (Boolean) isValid.invoke(inst, big),
                    big.length() + " B");

            File bad = sparseGguf(new File(tmp, "bad.gguf"), TOTAL, "HTML");
            check("bad magic rejected", !((Boolean) isValid.invoke(inst, bad)),
                    bad.length() + " B");

            // ---- 3. local HTTP server with Range support ----
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/model.gguf", ModelDownloaderSelfTest::serve);
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.gguf";

            final AtomicBoolean resumedSeen = new AtomicBoolean(false);
            Function1<Object, Unit> onProgress = p -> {
                try {
                    Object v = p.getClass().getMethod("getResumed").invoke(p);
                    if (Boolean.TRUE.equals(v)) resumedSeen.set(true);
                } catch (Exception ignored) {
                }
                return Unit.INSTANCE;
            };
            Function0<Boolean> notCancelled = () -> false;

            // ---- 4. real 206 resume: pre-create a sparse .part missing the last 1000 B ----
            File part = sparseGguf(new File(tmp, "model.gguf.part"), TOTAL - 1000, "GGUF");
            File target = new File(tmp, "model.gguf");
            Object result;
            try {
                result = fetch.invoke(inst, url, part, target, 0, onProgress, notCancelled);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("resume fetch threw: " + e.getCause(), e.getCause());
            }
            check("resume fetch returns target", result.equals(target), String.valueOf(result));
            check("Progress.resumed == true", resumedSeen.get(), "");
            check("final size exact", target.length() == TOTAL, target.length() + " B");
            check("final magic GGUF", first4(target).equals("GGUF"), first4(target));
            check(".part removed after rename", !part.exists(), "");

            // ---- 5. cancel keeps .part for next run ----
            File part2 = sparseGguf(new File(tmp, "cancel.gguf.part"), TOTAL - 1000, "GGUF");
            File target2 = new File(tmp, "cancel.gguf");
            Function0<Boolean> alwaysCancel = () -> true;
            String causeName = "none";
            try {
                fetch.invoke(inst, url, part2, target2, 0, onProgress, alwaysCancel);
            } catch (InvocationTargetException e) {
                causeName = e.getCause().getClass().getSimpleName();
            }
            check("cancel throws DownloadCancelledException",
                    causeName.equals("DownloadCancelledException"), causeName);
            check("cancel keeps .part at resume point",
                    part2.exists() && part2.length() == TOTAL - 1000,
                    part2.length() + " B");
            check("cancel does not create target", !target2.exists(), "");
        } finally {
            if (server != null) server.stop(0);
            File[] left = tmp.listFiles();
            if (left != null) for (File f : left) f.delete();
            tmp.delete();
        }

        System.out.println("---");
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** Logical-size file without writing gigabytes: only magic + tail are touched. */
    static File sparseGguf(File f, long size, String magic) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            r.setLength(size);
            r.seek(0);
            r.write(magic.getBytes(StandardCharsets.US_ASCII));
        }
        return f;
    }

    static String first4(File f) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[4];
            r.readFully(b);
            return new String(b, StandardCharsets.US_ASCII);
        }
    }

    /** Serves a logical TOTAL-byte GGUF; honors "bytes=N-" so only the tail moves. */
    static void serve(HttpExchange ex) {
        try {
            String range = ex.getRequestHeaders().getFirst("Range");
            long start = 0;
            if (range != null && range.startsWith("bytes=")) {
                String head = range.substring(6).split("-")[0];
                if (!head.isEmpty()) start = Long.parseLong(head);
            }
            long remaining = TOTAL - start;
            if (start > 0) {
                ex.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + (TOTAL - 1) + "/" + TOTAL);
                ex.sendResponseHeaders(206, remaining);
            } else {
                ex.sendResponseHeaders(200, remaining);
            }
            byte[] buf = new byte[64 * 1024];
            long written = 0;
            try (OutputStream os = ex.getResponseBody()) {
                while (written < remaining) {
                    int n = (int) Math.min(buf.length, remaining - written);
                    if (start == 0 && written == 0) {
                        System.arraycopy("GGUF".getBytes(StandardCharsets.US_ASCII), 0, buf, 0, 4);
                    }
                    os.write(buf, 0, n);
                    written += n;
                }
            }
        } catch (Exception ignored) {
            // client may disconnect on cancel; nothing to assert on the server side
        } finally {
            ex.close();
        }
    }
}
