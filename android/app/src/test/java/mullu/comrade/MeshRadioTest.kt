package mullu.comrade

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The multicast lock is what makes mDNS receive anything on a real phone, so
 * the one thing that must never happen is [MeshRadio] throwing on the path
 * that reports mesh status — that path also carries every peer-count update
 * the UI draws, and taking it down would trade a degraded mesh for a dead
 * indicator.
 *
 * A JVM unit test has no Android context, which is exactly the shape of the
 * risky case: `attach` never ran, and `setActive` still has to be a no-op
 * rather than a `WifiManager` NPE or an "android.util.Log not mocked" crash.
 * That is why the assertions below look thin — the behaviour under test is
 * "returns quietly, holds nothing", and reaching any Android API at all would
 * fail the test by throwing.
 */
class MeshRadioTest {

    @Test
    fun withNoContextAttachedTheLockIsANoOpRatherThanACrash() {
        MeshRadio.setActive(true)
        assertFalse("nothing can be held without a WifiManager", MeshRadio.isHeld)

        MeshRadio.setActive(false)
        assertFalse(MeshRadio.isHeld)
    }

    @Test
    fun repeatedCallsAreIdempotent() {
        // Status events are not deduplicated upstream: the core re-emits
        // `MeshStatusChanged(active = true)` on every peer-count change, so
        // `setActive(true)` arrives many times for one running mesh and must
        // not stack acquisitions (the lock is deliberately not reference
        // counted for this reason).
        repeat(3) { MeshRadio.setActive(true) }
        assertFalse(MeshRadio.isHeld)
        repeat(3) { MeshRadio.setActive(false) }
        assertFalse(MeshRadio.isHeld)
    }

    @Test
    fun theMeshStatusMonitorDrivesItWithoutBlowingUp() {
        // The real call path: every status update funnels through here, so if
        // this throws the peer-count indicator dies with it.
        MeshStatusMonitor.update(ComradeCore.MeshStatus(active = true, peerCount = 2))
        MeshStatusMonitor.update(ComradeCore.MeshStatus(active = false, peerCount = 0))
        assertFalse(MeshRadio.isHeld)
    }
}
