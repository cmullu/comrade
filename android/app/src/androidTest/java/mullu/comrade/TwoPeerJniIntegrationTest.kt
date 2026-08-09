package mullu.comrade

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.comrade.BridgeEventListener
import uniffi.comrade.Comrade
import uniffi.comrade_ui.BridgeEvent
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * COMMS-03: two independent `Comrade` FFI instances — own vault directory
 * each — exchanging an encrypted DM across the **real** JNI/uniffi boundary
 * this device runs. This is the Android-side complement to
 * `crates/comrade_ui/tests/two_peer_integration.rs`: that suite proves the
 * Rust signaling logic is correct against an in-process fake relay; this one
 * proves the generated Kotlin bindings — async `suspend fun` bridging,
 * `BridgeEventListener` callback delivery across the FFI boundary — actually
 * work on a real device, which pure Rust tests cannot touch at all.
 *
 * ## Isolated relay, not the public internet
 * Both instances connect to one relay address read from the
 * `comradeTestRelayUrl` instrumentation argument (see
 * `deploy/test-relay/README.md` for how CI supplies it). CI reaches it with
 * `adb reverse tcp:8090 tcp:8090`, so the address is the device's own
 * `127.0.0.1:8090` — **not** the `10.0.2.2` host loopback the emulator
 * documentation offers, which depends on the emulated radio NAT and is not
 * reliably routable from an app on an API 35 netsim-Wi-Fi image. Without the
 * argument this test is **skipped**, not silently pointed at the public relay
 * pool — a two-peer test flaking on a real relay's availability/rate limits
 * would defeat the point of an isolated test environment.
 *
 * **CI passes it, and asserts that it did.** Until 2026-08-09 it did not: the
 * device lanes ran `connectedDebugAndroidTest` with no relay argument, so every
 * test in this file skipped on every run and the lane was green having proven
 * nothing at all. [relayUrlOrSkip] is where that cannot happen again.
 *
 * ## Two instances, not two installations — and the constructor that makes that
 * actually true
 * This uses two in-process `Comrade` objects rather than two separately
 * installed app IDs, via [Comrade.newIsolatedWithRelays], which builds a
 * `ComradeRuntime` of its own per handle.
 *
 * **It used to call `newWithRelays`, and that is why the first non-skipping run
 * of this file was red on all three tests.** That constructor returned a handle
 * onto the one process-global runtime, so "alice" and "bob" were the same
 * runtime: `unlock_vault` is idempotent, so bob's unlock handed back *alice's*
 * identity and both sides shared one npub. Alice DMing bob was alice DMing
 * herself, no `IncomingMessageRequest` was ever emitted, and the three
 * assertions all read "nothing arrived" with no hint as to why. The regression
 * test is `two_isolated_handles_are_two_separate_runtimes` in `comrade_jni`,
 * which runs on every push and fails in one second rather than fifteen minutes.
 *
 * Two runtimes in one process is safe only because each is given a vault
 * directory of its own — redb's exclusive lock is per directory. Keep the
 * `aliceDir`/`bobDir` split in every test here.
 *
 * `build.gradle.kts`'s `deviceHarnessRole` property is the mechanism that *does*
 * produce two installable app IDs with isolated storage for a fuller cross-app
 * harness — two processes, two `MediaPlayer`s, real BLE/Wi-Fi between them.
 * That is still future work: what this file can prove is the bindings and the
 * wire, not two apps on one handset.
 */
@RunWith(AndroidJUnit4::class)
class TwoPeerJniIntegrationTest {

    private class RecordingListener : BridgeEventListener {
        val events = ConcurrentLinkedQueue<BridgeEvent>()
        override fun onEvent(event: BridgeEvent) {
            events.offer(event)
        }
    }

    /**
     * The relay to use, or a skip — **and the polarity here is the opposite of
     * the obvious one, deliberately.**
     *
     * Locally, a skip is right: somebody running the suite on a laptop with no
     * relay container should not get a red test, and must never get a silent
     * fall back to the public relay pool, where a two-peer test would flake on
     * somebody else's rate limit.
     *
     * In CI a skip is the failure. These tests existed and skipped on every run
     * for months because nothing passed `comradeTestRelayUrl`, and a skipped test
     * is a green test — so the lane meant to be the evidence for two peers
     * talking was evidence of nothing.
     *
     * The first attempt at closing that was a `comradeRequireRelay=true` flag the
     * workflow passed to demand a relay, and **it could not work**: the flag and
     * the URL travel by the same mechanism, so anything that stops the
     * instrumentation arguments arriving drops both at once — the demand
     * disappears with the thing it was demanding, and every test skips green
     * again. It caught only a human deleting one line and leaving the other.
     *
     * So the demand is inverted: **skipping is what has to be asked for.** CI
     * passes nothing and gets a red test the moment the relay wiring breaks; a
     * laptop passes `-e comradeAllowRelaySkip true` and gets its skip. Absence of
     * configuration is now strict rather than lenient, which is the only polarity
     * that fails in the safe direction.
     */
    private fun relayUrlOrSkip(): String {
        val args = InstrumentationRegistry.getArguments()
        val relayUrl = args.getString("comradeTestRelayUrl")
        // `String?.toBoolean()` maps null to false, so an absent argument means
        // "not allowed to skip" — which is the strict reading, on purpose.
        val skipAllowed = args.getString("comradeAllowRelaySkip").toBoolean()
        if (relayUrl.isNullOrBlank() && !skipAllowed) {
            throw AssertionError(
                "no comradeTestRelayUrl instrumentation argument, and skipping was not " +
                    "explicitly allowed. In CI this means android-apk.yml's 'Start isolated " +
                    "test relay' step or its -Pandroid.testInstrumentationRunnerArguments.* " +
                    "wiring is broken, and this lane must not go green having proven nothing. " +
                    "Locally, pass -e comradeAllowRelaySkip true (see deploy/test-relay/README.md).",
            )
        }
        org.junit.Assume.assumeTrue(
            "requires an isolated test relay — pass -e comradeTestRelayUrl <ws-url> " +
                "(see deploy/test-relay/README.md); skipping rather than hitting the public relay pool",
            !relayUrl.isNullOrBlank(),
        )
        return requireNotNull(relayUrl)
    }

    private suspend fun waitFor(
        listener: RecordingListener,
        timeoutMs: Long = 15_000L,
        predicate: (BridgeEvent) -> Boolean,
    ): BridgeEvent? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            listener.events.poll()?.let { if (predicate(it)) return@withTimeoutOrNull it }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    @Test
    fun two_real_ffi_instances_exchange_a_gated_dm() {
        val testRelayUrl = relayUrlOrSkip()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val aliceDir = File(context.filesDir, "jni-2peer-alice")
        val bobDir = File(context.filesDir, "jni-2peer-bob")

        val alice = Comrade.newIsolatedWithRelays(listOf(testRelayUrl))
        val bob = Comrade.newIsolatedWithRelays(listOf(testRelayUrl))
        val aliceEvents = RecordingListener()
        val bobEvents = RecordingListener()

        runBlocking {
            alice.setEventListener(aliceEvents)
            bob.setEventListener(bobEvents)
            alice.unlockVault(aliceDir.absolutePath, "pin")
            bob.unlockVault(bobDir.absolutePath, "pin")
        }

        // Sync (non-suspend) FFI calls — called directly, no runBlocking needed.
        val aliceNpub = requireNotNull(alice.currentIdentity()).npub
        val bobNpub = requireNotNull(bob.currentIdentity()).npub

        runBlocking { alice.sendDm(bobNpub, "hello across the real JNI boundary") }

        val received = runBlocking {
            waitFor(bobEvents) { it is BridgeEvent.IncomingMessageRequest }
        }
        assertNotNull("bob's real Comrade instance must receive alice's DM as a message request", received)
        val request = (received as BridgeEvent.IncomingMessageRequest).v1
        assertEquals(aliceNpub, request.peer)
    }

    /**
     * The comrade-presence half of the same contract: a beacon crossing two
     * real FFI instances, and the `ComradePresence` event arriving through the
     * generated `BridgeEventListener`.
     *
     * The presence *rules* are proven in `two_peer_integration.rs`; what only a
     * device can show is that the new bindings themselves work — `setComrade`
     * as a generated `suspend fun`, and an enum variant with named fields
     * (`peer`/`name`/`online`/`at`, unlike the tuple-shaped variants above)
     * surviving the uniffi round-trip.
     */
    @Test
    fun two_real_ffi_instances_exchange_comrade_presence() {
        val testRelayUrl = relayUrlOrSkip()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val aliceDir = File(context.filesDir, "jni-presence-alice")
        val bobDir = File(context.filesDir, "jni-presence-bob")

        val alice = Comrade.newIsolatedWithRelays(listOf(testRelayUrl))
        val bob = Comrade.newIsolatedWithRelays(listOf(testRelayUrl))
        val aliceEvents = RecordingListener()
        val bobEvents = RecordingListener()

        runBlocking {
            alice.setEventListener(aliceEvents)
            bob.setEventListener(bobEvents)
            alice.unlockVault(aliceDir.absolutePath, "pin")
            bob.unlockVault(bobDir.absolutePath, "pin")
        }
        val aliceNpub = requireNotNull(alice.currentIdentity()).npub
        val bobNpub = requireNotNull(bob.currentIdentity()).npub

        // Presence only flows inside an accepted conversation, so get past the
        // stranger gate first — same path a real pair of users takes.
        runBlocking {
            alice.sendDm(bobNpub, "hi bob")
            waitFor(bobEvents) { it is BridgeEvent.IncomingMessageRequest }
            bob.acceptRequest(aliceNpub)
            // Each side chooses the other; either beacon alone would tell the
            // other nothing, which is the property the Rust suite covers.
            alice.setComrade(bobNpub, true)
            bob.setComrade(aliceNpub, true)
        }

        val presence = runBlocking {
            waitFor(aliceEvents) { it is BridgeEvent.ComradePresence && it.online }
        }
        assertNotNull("alice's real Comrade instance must be told bob is online", presence)
        assertEquals(bobNpub, (presence as BridgeEvent.ComradePresence).peer)

        val bobRow = alice.comrades().single { it.npub == bobNpub }
        assertEquals(true, bobRow.online)
        assertEquals(true, bobRow.peerMarkedUs)
    }

    /**
     * A whole listen-together session between two real FFI instances over a real
     * socket: invite, join, and a run of transport commands.
     *
     * **This is the lane the field bugs would have needed.** The protocol itself
     * is proven in `two_peer_integration.rs` against an in-process relay, and
     * every arithmetic decision is unit-tested twice over — and none of that
     * touched the two things that actually broke on a pair of handsets: whether
     * `together_start` and `together_set_state` can be *called* the way the app
     * calls them, and whether a rapid run of commands arrives in the order it
     * was sent.
     *
     * ### What the ordering half is for, precisely
     *
     * `together_set_state` takes the session's next Lamport sequence number
     * **inside the call**, so concurrent senders can be numbered in the opposite
     * order to the taps that produced them. Pause-then-play arriving as
     * play-then-pause is a session that plays when the person asked for silence.
     * `TogetherManager.sendOut` serialises through one FIFO queue to prevent
     * exactly that; this asserts the property at the far end, where it matters,
     * rather than trusting the queue's implementation.
     *
     * It also asserts the property the *core* provides and the queue relies on:
     * `pos_ms` arrives as sent. Three commands whose positions are distinct is
     * enough to detect a reorder without depending on how the events are
     * batched.
     *
     * Deliberately no player, no `TogetherManager`, and no Compose. Those import
     * Android and are covered — as far as anything covers them — by the JVM
     * decision tests and the type-check lanes. What only a device can prove is
     * the generated `suspend fun` bridge under a session's traffic.
     */
    @Test
    fun two_real_ffi_instances_run_a_session_and_commands_arrive_in_order() {
        val testRelayUrl = relayUrlOrSkip()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val aliceDir = File(context.filesDir, "jni-together-alice")
        val bobDir = File(context.filesDir, "jni-together-bob")

        val alice = Comrade.newIsolatedWithRelays(listOf(testRelayUrl))
        val bob = Comrade.newIsolatedWithRelays(listOf(testRelayUrl))
        val aliceEvents = RecordingListener()
        val bobEvents = RecordingListener()

        runBlocking {
            alice.setEventListener(aliceEvents)
            bob.setEventListener(bobEvents)
            alice.unlockVault(aliceDir.absolutePath, "pin")
            bob.unlockVault(bobDir.absolutePath, "pin")
        }
        val aliceNpub = requireNotNull(alice.currentIdentity()).npub
        val bobNpub = requireNotNull(bob.currentIdentity()).npub

        // A session may only be started with an accepted contact — the same
        // stranger gate `a_stranger_cannot_start_a_watch_party_with_an_unaccepted_target`
        // proves in Rust — so get past it the way a real pair does.
        runBlocking {
            alice.sendDm(bobNpub, "put something on?")
            waitFor(bobEvents) { it is BridgeEvent.IncomingMessageRequest }
            bob.acceptRequest(aliceNpub)
        }

        // One album both of them "have". The length is the only thing a
        // `LocalFile` invitation identifies it by (see `TogetherContent`), and
        // no file is opened here: this test is about the wire and the bindings.
        val trackMs = 187_000UL
        runBlocking {
            alice.togetherStart(
                bobNpub,
                uniffi.comrade_core.TogetherContent.LocalFile(trackMs, null),
            )
        }

        val invited = runBlocking { waitFor(bobEvents) { it is BridgeEvent.TogetherInvited } }
        assertNotNull("bob's instance must be invited to the session", invited)
        val invite = (invited as BridgeEvent.TogetherInvited).v1
        assertEquals(aliceNpub, invite.peer)

        runBlocking { bob.togetherJoin() }
        val joined = runBlocking { waitFor(aliceEvents) { it is BridgeEvent.TogetherJoined } }
        assertNotNull("alice must be told bob joined, or the session never goes live", joined)
        assertEquals(invite.sessionId, (joined as BridgeEvent.TogetherJoined).sessionId)

        // The run that would have caught the reordering hazard. Sent back to
        // back with no waiting between them, which is what a finger on a
        // transport actually produces and what the old per-call dispatcher
        // could reorder.
        //
        // `effectiveInMs` of 0 throughout: this test has no player to defer
        // against, and a non-zero value would only ask the receiver to wait.
        val sent = listOf(
            30_000UL to true,
            60_000UL to false,
            90_000UL to true,
        )
        runBlocking {
            for ((posMs, playing) in sent) {
                alice.togetherSetState(posMs, playing, 0UL)
            }
        }

        val arrived = mutableListOf<Pair<ULong, Boolean>>()
        runBlocking {
            // Collected until we have as many as were sent, or the wait gives
            // up — an assertion on the list is a better failure message than a
            // timeout on the third one.
            while (arrived.size < sent.size) {
                val next = waitFor(bobEvents) { it is BridgeEvent.TogetherCommand }
                    ?: break
                val cmd = (next as BridgeEvent.TogetherCommand).v1
                arrived += cmd.posMs to cmd.playing
            }
        }

        // The play/pause sequence is compared exactly — that is what a reorder
        // scrambles, and what turns "pause" into a session that keeps playing.
        assertEquals(
            "play/pause must arrive in the order it was sent — a reorder here is a " +
                "session that plays when the person asked for silence",
            sent.map { it.second },
            arrived.map { it.second },
        )

        // Positions get a floor and a ceiling rather than equality, and the
        // reason is a contract rather than tolerance for flakiness: a *playing*
        // command's `posMs` is carried forward through the message's flight time
        // (see `TogetherCommandDto.posMs`) so the receiver applies it as-is
        // instead of compensating twice, while a paused playhead does not
        // advance. The hermetic twin of this test in
        // `crates/comrade_ui/tests/two_peer_integration.rs` was written asserting
        // equality first and failed on exactly that — 30_000 arriving as 30_016.
        // Over a real socket the flight time is larger, so the ceiling is too.
        val flightCeilingMs = 10_000UL
        sent.zip(arrived).forEachIndexed { i, (wasSent, got) ->
            val (sentPos, sentPlaying) = wasSent
            val (gotPos, _) = got
            org.junit.Assert.assertTrue(
                "command $i arrived at $gotPos for a send of $sentPos",
                gotPos >= sentPos && gotPos < sentPos + flightCeilingMs,
            )
            if (!sentPlaying) {
                assertEquals(
                    "a paused playhead must not be carried forward — command $i",
                    sentPos,
                    gotPos,
                )
            }
        }

        // And the session comes down cleanly on the other side, which is what
        // frees the pairing rather than leaving it to the TTL.
        runBlocking { alice.togetherEnd() }
        val ended = runBlocking { waitFor(bobEvents) { it is BridgeEvent.TogetherEnded } }
        assertNotNull("bob must be told the session ended", ended)
        assertEquals(true, (ended as BridgeEvent.TogetherEnded).byPeer)
    }
}
