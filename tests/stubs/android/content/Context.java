package android.content;

/**
 * JVM-only stub. ModelDownloader's public API mentions Context, but the methods
 * under test (parseTotal / isValid / fetch) never touch it. This lets the JVM
 * load the real Kotlin .class without pulling in the Android SDK.
 * Never shipped in the APK.
 */
public class Context {
}
