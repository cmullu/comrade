package mullu.comrade

/**
 * The app's `Application`, for the Flutter build.
 *
 * Extends the preserved [ComradeApplication] rather than replacing it, because
 * everything that class does is orthogonal to which UI toolkit sits on top:
 *
 *  - it warms `libcomrade_jni.so` on a background thread at process start, so
 *    the multi-megabyte `System.loadLibrary` does not land on the main thread
 *    during the first frame;
 *  - it owns [ComradeApplication.appScope], the process-lifetime scope that
 *    `ComradeCore.initializeEventBridge()` is awaited on — the fix for the race
 *    where an event published before a listener subscribed was silently dropped
 *    (AUDIT COMMS-01; a zero-receiver `tokio::sync::broadcast` send is a no-op).
 *
 * The one thing added here is starting [CallStateReactor]. It has to happen at
 * *process* start rather than at engine attach: `RelayConnectionService` is
 * `START_STICKY`, so a process the OS restarted for the service alone may never
 * attach a Flutter engine at all — and an incoming call arriving in that state
 * still has to ring. Starting it here is the only placement that covers it.
 *
 * Referenced from `AndroidManifest.xml` as `android:name=".ComradeFlutterApplication"`,
 * replacing Flutter's default `${applicationName}` placeholder. The v2 embedding
 * does not require a `FlutterApplication` base class, so subclassing ours is safe.
 */
class ComradeFlutterApplication : ComradeApplication() {
    override fun onCreate() {
        super.onCreate()
        CallStateReactor.start(this, appScope)
    }
}
