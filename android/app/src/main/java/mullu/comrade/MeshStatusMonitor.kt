package mullu.comrade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live off-grid mesh connectivity, as a [StateFlow] any screen can collect for
 * a persistent status indicator.
 *
 * [ComradeCore.pollEvent] drains a single process-global native receiver, so
 * only one pump loop may ever consume it ([RelayConnectionService] owns that
 * loop for Chitthis/DMs/requests). This object holds no polling logic of its
 * own — [RelayConnectionService]'s [ChatEventRouter] pushes updates in as
 * `mesh_status_changed` events arrive, and seeds the initial value from
 * [ComradeCore.meshStatusTyped].
 *
 * `peerCount` is the number of peers a message could actually be **delivered**
 * to, not the number mDNS has merely announced. The two differ for as long as a
 * dial and a topic subscription take, and reporting the larger one is what let
 * the app show a peer badge while every send failed with `InsufficientPeers`.
 */
object MeshStatusMonitor {
    private val _status = MutableStateFlow(ComradeCore.MeshStatus(active = false, peerCount = 0))
    val status: StateFlow<ComradeCore.MeshStatus> = _status.asStateFlow()

    fun update(status: ComradeCore.MeshStatus) {
        _status.value = status
        // The single choke point for "is the mesh running", so the WiFi
        // multicast lock is taken and dropped here rather than at each of the
        // callers that push a status in — two call sites would be two chances
        // for one of them to stop doing it. See [MeshRadio] for why mDNS on
        // Android receives nothing at all without that lock.
        MeshRadio.setActive(status.active)
    }
}
