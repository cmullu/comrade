package mullu.comrade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot "open this tab" requests coming from outside the composition —
 * today, a tapped notification.
 *
 * A [StateFlow] rather than an Activity field so the request survives the gap
 * between the intent arriving and [MainActivity]'s shell existing: tapping
 * "the companion model is ready" while the vault is locked parks the request
 * here, and the user lands on Tara right after unlocking instead of on the
 * chat list. The consumer clears it with [consume] so a configuration change
 * doesn't re-navigate.
 */
object AppNavigation {

    /** Intent extra carrying a [MainTab]-ish key, e.g. [mullu.comrade.model.ModelCatalog.TAB_TARA]. */
    const val EXTRA_OPEN_TAB = "mullu.comrade.extra.OPEN_TAB"

    private val _requestedTab = MutableStateFlow<String?>(null)
    val requestedTab: StateFlow<String?> = _requestedTab

    /** Record a navigation request; blank/absent keys are ignored. */
    fun request(tab: String?) {
        if (!tab.isNullOrBlank()) _requestedTab.value = tab
    }

    /** Called once the request has been honoured. */
    fun consume() {
        _requestedTab.value = null
    }
}
