package mullu.comrade.together

import mullu.comrade.together.MediaLibraryAccess.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When Comrade asks to read the music library.
 *
 * The permission names are asserted as literals rather than against
 * `Manifest.permission.*`: those are compile-time string constants, so comparing
 * one to itself would pass whichever branch [MediaLibraryAccess.permissionFor]
 * took. Asking for `READ_EXTERNAL_STORAGE` on Tiramisu is a request that can
 * never be granted, and this is the test that would catch it.
 */
class MediaLibraryAccessTest {

    @Test
    fun tiramisuGetsThePerTypePermissionAndOlderReleasesTheOldOne() {
        assertEquals("android.permission.READ_MEDIA_AUDIO", MediaLibraryAccess.permissionFor(33))
        assertEquals("android.permission.READ_MEDIA_AUDIO", MediaLibraryAccess.permissionFor(35))
        assertEquals(
            "android.permission.READ_EXTERNAL_STORAGE",
            MediaLibraryAccess.permissionFor(32),
        )
    }

    @Test
    fun grantedLooks() {
        assertEquals(Step.Look, MediaLibraryAccess.next(granted = true, askedBefore = false))
        assertEquals(Step.Look, MediaLibraryAccess.next(granted = true, askedBefore = true))
    }

    @Test
    fun theFirstRefusalIsTheLastAsk() {
        assertEquals(Step.Ask, MediaLibraryAccess.next(granted = false, askedBefore = false))
        // Not `Ask` again: Android stops showing the dialog after a refusal, so a
        // second request is a button that silently does nothing. The picker needs
        // no permission, so this route still works.
        assertEquals(Step.Picker, MediaLibraryAccess.next(granted = false, askedBefore = true))
    }
}
