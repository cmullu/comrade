package mullu.comrade

import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * The single Activity hosting the Flutter UI.
 *
 * Deliberately thin. Everything that used to live in the Compose
 * `MainActivity` — the call-state side effects, notification routing,
 * permission gating — has moved either into Dart (via the channels) or into
 * engine-independent native code ([CallStateReactor], [ComradePlugin]).
 * What is left here is the two things that genuinely need an *Activity*:
 *
 *  1. registering this Activity's window with [CallStateReactor] so an
 *     incoming call can light the screen over the keyguard;
 *  2. forwarding a notification's `AppNavigation.EXTRA_OPEN_TAB` to Dart.
 */
class MainActivity : FlutterActivity() {

    private val plugin = ComradePlugin()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(plugin)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallStateReactor.attachActivity(this)
        forwardRequestedTab(intent)
    }

    /**
     * `launchMode="singleTop"` (see the manifest) means a second tap on a
     * notification re-delivers here rather than creating a new Activity, so the
     * tab extra has to be read from both entry points.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardRequestedTab(intent)
    }

    override fun onDestroy() {
        CallStateReactor.detachActivity(this)
        super.onDestroy()
    }

    /**
     * Fire-and-forget. If the engine is not attached yet (a cold start from a
     * notification is exactly that case), `SystemChannel` stashes the request
     * and Dart picks it up with `consumePendingTab` once it is running.
     */
    private fun forwardRequestedTab(intent: Intent?) {
        val tab = intent?.getStringExtra(AppNavigation.EXTRA_OPEN_TAB) ?: return
        plugin.systemChannel?.requestTab(tab)
    }
}
