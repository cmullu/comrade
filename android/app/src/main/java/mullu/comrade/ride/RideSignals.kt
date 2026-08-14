package mullu.comrade.ride

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mullu.comrade.voice.ComradeTts

/**
 * The live ride board — the newest quick phrase and the newest route
 * suggestion, exactly the two slots [RideDecisions.card] ranks. Fed by
 * [mullu.comrade.RelayConnectionService]'s event arm rather than by a screen,
 * the same division `TogetherManager` uses and for the same reason: signals
 * arrive whether or not anyone is looking, and the screen only *reads*.
 *
 * Deliberately memory-only. A ride signal is a claim about now — core already
 * refuses one older than a minute, the card rule ages the rest out on screen,
 * and persisting "pull over" would only create somewhere for it to be wrong
 * later. A relaunch starts with an empty board the way it starts with no
 * together session.
 *
 * Speech and haptics happen **here, on arrival**, not on render: a driver's
 * phone is in a mount or a pocket, and a card that waited for a recomposition
 * to buzz would be a card that waited for the person it exists to interrupt.
 * Both are decided by [RideDecisions] against the urgency verdict core sent,
 * and both are inert until a screen declares a role via [setActive] — with no
 * role there is no driver to speak to, and an app that vibrates over ordinary
 * chat because someone once opened a screen would be a bug.
 */
object RideSignals {

    data class Board(
        val quick: RideDecisions.Sig? = null,
        val route: RideDecisions.Sig? = null,
    )

    private val _board = MutableStateFlow(Board())
    val board: StateFlow<Board> = _board.asStateFlow()

    /** The seat this device's open ride screen chose, or null when the screen
     *  is closed. Written from the screen's lifecycle only. */
    @Volatile
    private var activeRole: RideDecisions.Role? = null

    private var tts: ComradeTts? = null

    /** The ride screen is open as [role]; speech and haptics arm. */
    fun setActive(context: Context, role: RideDecisions.Role) {
        activeRole = role
        if (role == RideDecisions.Role.Driver && tts == null) {
            tts = ComradeTts(context)
        }
    }

    /** The ride screen closed; nothing arrives loudly any more. */
    fun setInactive() {
        activeRole = null
        tts?.shutdown()
        tts = null
    }

    /** One signal off the event pump. Board first, then the interruptions, so
     *  a throw in an OEM vibrator cannot lose the card. */
    fun onSignal(context: Context, sig: RideDecisions.Sig) {
        _board.update {
            if (sig.kind == "quick") it.copy(quick = sig) else it.copy(route = sig)
        }
        val role = activeRole ?: return
        RideDecisions.spokenLine(role, sig)?.let { line -> tts?.speak(line) }
        RideDecisions.hapticPattern(sig.urgency)?.let { pattern -> vibrate(context, pattern) }
    }

    /** For a fresh session's screen: a board carried over from a previous ride
     *  with someone else would draw their instructions under a new name. */
    fun clear() {
        _board.value = Board()
    }

    /** Silent — and harmless — on a device with no vibrator, and wrapped in
     *  [runCatching] like [mullu.comrade.ui.BreathHaptics]: some OEM vibrators
     *  throw on waveforms they dislike, and a buzz is never worth a crash. */
    private fun vibrate(context: Context, pattern: LongArray) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT))
        }
    }

    private const val NO_REPEAT = -1

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
