package mullu.comrade.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelDownloads]' bookkeeping — the process-wide state every download
 * surface observes, and the cancel flag the transfer loop polls.
 *
 * The object is deliberately process-wide (a download must outlive the screen
 * that started it), so these tests use a unique id each rather than resetting
 * shared state: coupling tests through a singleton is exactly the kind of
 * flake that is miserable to debug later.
 */
class ModelDownloadsTest {

    private fun freshId(name: String) = "test-$name-${System.nanoTime()}"

    @Test
    fun `an unknown model starts idle`() {
        assertEquals(ModelDownloadState.Idle, ModelDownloads.current(freshId("unknown")))
    }

    @Test
    fun `state is observable and per-model`() {
        val a = freshId("a")
        val b = freshId("b")
        val flowA = ModelDownloads.stateOf(a)

        assertSame("the same id must hand back the same flow", flowA, ModelDownloads.stateOf(a))
        assertNotSame("different models must not share a flow", flowA, ModelDownloads.stateOf(b))

        ModelDownloads.update(a, ModelDownloadState.Downloading(512, 2048))
        assertEquals(ModelDownloadState.Downloading(512, 2048), flowA.value)
        assertEquals("updating one model must not disturb another", ModelDownloadState.Idle, ModelDownloads.current(b))

        ModelDownloads.update(a, ModelDownloadState.Installing)
        assertEquals(ModelDownloadState.Installing, flowA.value)
        ModelDownloads.update(a, ModelDownloadState.Ready)
        assertEquals(ModelDownloadState.Ready, flowA.value)
    }

    @Test
    fun `progress carries bytes and total for the bar`() {
        val id = freshId("progress")
        ModelDownloads.update(id, ModelDownloadState.Downloading(1_000, 4_000))
        val state = ModelDownloads.current(id)
        assertTrue(state is ModelDownloadState.Downloading)
        state as ModelDownloadState.Downloading
        assertEquals(1_000L, state.bytesRead)
        assertEquals(4_000L, state.totalBytes)
        assertEquals(25, downloadPercent(state.bytesRead, state.totalBytes))
    }

    @Test
    fun `cancel is per-model and clearable`() {
        val target = freshId("cancel-target")
        val other = freshId("cancel-other")

        assertFalse(ModelDownloads.isCancelled(target))
        ModelDownloads.cancel(target)
        assertTrue(ModelDownloads.isCancelled(target))
        assertFalse("cancelling one model must not abort another", ModelDownloads.isCancelled(other))

        ModelDownloads.clearCancel(target)
        assertFalse(ModelDownloads.isCancelled(target))
    }

    @Test
    fun `a stale cancel cannot abort the next download`() {
        // Cancelling while nothing is running leaves the flag set; the service
        // clears it when it starts, so the *next* transfer must not inherit it.
        val id = freshId("stale")
        ModelDownloads.cancel(id)
        assertTrue(ModelDownloads.isCancelled(id))

        ModelDownloads.clearCancel(id) // what ModelDownloadService does on start
        ModelDownloads.update(id, ModelDownloadState.Downloading(0, 100))
        assertFalse("the new download must start un-cancelled", ModelDownloads.isCancelled(id))
    }

    @Test
    fun `failure carries a message the UI can show`() {
        val id = freshId("failed")
        ModelDownloads.update(id, ModelDownloadState.Failed("checksum mismatch"))
        val state = ModelDownloads.current(id)
        assertTrue(state is ModelDownloadState.Failed)
        assertEquals("checksum mismatch", (state as ModelDownloadState.Failed).message)
    }
}
