package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the transport ordering behind the app-bar switch: which route leads,
 * that the other one is never dropped, and what the local glyph reports.
 *
 * The Dart port (`app/test/transport_precedence_test.dart`) asserts the same
 * table, so a change here that isn't mirrored there shows up as a failing test
 * on the other frontend rather than as silent UI drift.
 */
class TransportPrecedenceTest {

    @Test
    fun relaysLeadUntilTheUserSaysOtherwise() {
        assertEquals(
            TransportRoute.Relay,
            TransportPrecedence.leadOf(TransportPrecedence.RELAY_FIRST_WORKSPACE),
        )
        // An unknown key is not a reason to route strangely — the default order
        // is the safe one, because relays reach people who are not nearby.
        assertEquals(TransportRoute.Relay, TransportPrecedence.leadOf("SomethingNew"))
    }

    @Test
    fun offGridPutsTheLocalNetworkFirst() {
        assertEquals(
            listOf(TransportRoute.LocalNetwork, TransportRoute.Relay),
            TransportPrecedence.orderOf(TransportPrecedence.LOCAL_FIRST_WORKSPACE),
        )
    }

    @Test
    fun precedenceIsAnOrderNotAnExclusion() {
        // Whichever way round, both routes are still on the list: a message the
        // preferred transport cannot carry must still take the other one.
        for (key in listOf(
            TransportPrecedence.RELAY_FIRST_WORKSPACE,
            TransportPrecedence.LOCAL_FIRST_WORKSPACE,
        )) {
            assertEquals(
                setOf(TransportRoute.Relay, TransportRoute.LocalNetwork),
                TransportPrecedence.orderOf(key).toSet(),
            )
        }
    }

    @Test
    fun switchingRoundTripsThroughTheWorkspaceKey() {
        for (lead in TransportRoute.entries) {
            assertEquals(lead, TransportPrecedence.leadOf(TransportPrecedence.workspaceFor(lead)))
        }
    }

    @Test
    fun theCoupleSandboxIsNotATransportOrdering() {
        assertTrue(TransportPrecedence.isSwitchable(TransportPrecedence.RELAY_FIRST_WORKSPACE))
        assertTrue(TransportPrecedence.isSwitchable(TransportPrecedence.LOCAL_FIRST_WORKSPACE))
        assertFalse(TransportPrecedence.isSwitchable("CoupleSandboxSakha"))
        assertFalse(TransportPrecedence.isSwitchable("CoupleSandboxSakhi"))
    }

    @Test
    fun theLocalGlyphSeparatesOffFromLookingFromReaching() {
        assertEquals(
            LocalMeshState.Off,
            TransportPrecedence.meshStateOf(active = false, peerCount = 0),
        )
        // Peers reported while the engine is down are stale, not reachable.
        assertEquals(
            LocalMeshState.Off,
            TransportPrecedence.meshStateOf(active = false, peerCount = 4),
        )
        assertEquals(
            LocalMeshState.Searching,
            TransportPrecedence.meshStateOf(active = true, peerCount = 0),
        )
        assertEquals(
            LocalMeshState.Reaching,
            TransportPrecedence.meshStateOf(active = true, peerCount = 1),
        )
    }
}
