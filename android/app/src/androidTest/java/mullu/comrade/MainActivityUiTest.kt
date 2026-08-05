package mullu.comrade

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * On-device journey test for the Telegram-like flow: the onboarding door
 * renders without blocking on the native core, creating an identity (username
 * + passcode) unlocks the vault through real Rust crypto, and the main shell
 * (Chats / Journal / Feed / Tara, with Settings reached from the navigation
 * drawer) comes up with working bottom navigation.
 *
 * The test adapts to residual state: on a fresh emulator it walks the create
 * path; if a previous run on the same device already created the vault (or the
 * process still holds the unlocked runtime) it unlocks — with the same
 * passcode — or lands straight in the shell.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    // Grant POST_NOTIFICATIONS *before* the activity launches: the shell's
    // first-run notification prompt would otherwise pop a system dialog over
    // the app, pausing MainActivity — and a paused activity exposes no
    // queryable Compose hierarchy, which fails the semantics assertions below.
    // The outer rule runs first, so the permission is already granted when
    // MainShell mounts and the app never prompts.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(composeRule)

    private fun hasText(text: String) =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasTag(tag: String) =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** The onboarding error line's text, when one is showing. */
    private fun onboardingError(): String? {
        val node = composeRule.onAllNodesWithTag("onboarding-error").fetchSemanticsNodes()
            .firstOrNull() ?: return null
        if (!node.config.contains(SemanticsProperties.Text)) return null
        return node.config[SemanticsProperties.Text].joinToString()
    }

    /**
     * Hide the soft keyboard before tapping something it might be covering.
     *
     * [Espresso.closeSoftKeyboard] resolves a root view **with window focus**
     * (`RootViewPicker`), and on a cold emulator that focus can lag the Compose
     * hierarchy — the app is drawn and queryable while the window has not been
     * granted focus yet. Espresso only waits 10s and then fails with
     * `RootViewWithoutFocusException`, which is what flaked this test on CI.
     * Waiting for focus first makes the precondition explicit instead of racing
     * it; if focus genuinely never arrives that is a real problem and this
     * fails with a message that says so, rather than an opaque root dump.
     */
    private fun dismissKeyboard() {
        // Read the flag straight off the activity rather than hopping to the UI
        // thread: it is a null-safe field read, and because this is a polling
        // loop a stale `false` costs one extra poll rather than a wrong answer.
        composeRule.waitUntil(timeoutMillis = FOCUS_TIMEOUT_MS) {
            composeRule.activity.hasWindowFocus()
        }
        Espresso.closeSoftKeyboard()
    }

    private fun submitOnboarding() {
        // Typing opens the soft keyboard, which can cover the submit button and
        // swallow the injected tap — close it and scroll the button into view.
        dismissKeyboard()
        composeRule.onNodeWithTag("onboarding-submit").performScrollTo().performClick()
    }

    @Test
    fun onboardingLeadsToChatsShell() {
        // The startup check resolves into one of three doors.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            hasText("Create my identity") || hasText("Unlock") || hasText("Chats")
        }

        if (hasText("Create my identity")) {
            composeRule.onNodeWithTag("onboarding-username").performTextInput(USERNAME)
            composeRule.onNodeWithTag("onboarding-passcode").performTextInput(PASSCODE)
            composeRule.onNodeWithTag("onboarding-confirm").performTextInput(PASSCODE)
            submitOnboarding()
        } else if (hasText("Unlock")) {
            composeRule.onNodeWithTag("onboarding-passcode").performTextInput(PASSCODE)
            submitOnboarding()
        }

        // Argon2 key stretching + engine construction run off the UI thread;
        // the shell appears when the vault is open. Fail fast — with the
        // on-screen message — if onboarding surfaced an error instead.
        try {
            composeRule.waitUntil(timeoutMillis = 120_000) {
                hasText("Chats") || onboardingError() != null
            }
        } catch (timeout: ComposeTimeoutException) {
            // Say what was actually on screen: a stuck onboarding form (the tap
            // never landed) and a genuinely slow unlock are very different bugs,
            // and the bare timeout distinguishes neither.
            val screen = if (hasTag("onboarding-submit")) {
                "the onboarding form is still showing — the submit tap did not take effect"
            } else {
                "neither the shell nor the onboarding form is showing"
            }
            throw AssertionError(
                "Vault never opened within 120s: $screen " +
                    "(error line: ${onboardingError() ?: "none"})",
                timeout,
            )
        }
        onboardingError()?.let { message ->
            throw AssertionError("Onboarding reported an error: $message")
        }

        // The IME may still be up from the onboarding fields; drop it so taps
        // reach the bottom navigation.
        dismissKeyboard()

        // Bottom navigation reaches the Feed section.
        composeRule.onNodeWithText("Feed").performClick()
        composeRule
            .onNodeWithText("Public — anyone on the network can read this.")
            .assertIsDisplayed()

        // Settings is a pushed screen now, reached from the navigation drawer
        // (Telegram-style) rather than a bottom-nav tab. The hamburger only
        // lives on the chat list, so return there before opening the drawer.
        composeRule.onNodeWithText("Chats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav-drawer-button").performClick()
        composeRule.waitForIdle()

        // The drawer's profile header shows our @handle…
        composeRule.onNodeWithText("@$USERNAME").assertIsDisplayed()

        // …and its Tasks item opens the task list. Asserted on a device because
        // this screen shipped in a state where opening it killed the process, and
        // nothing in the JVM lane could see that: `TaskList`'s decisions are all
        // unit-tested, and the crash was in the *composition* around them. The
        // empty-state node is the proof it survived a full load — it only appears
        // once the store has answered, which is the recomposition that failed.
        composeRule.onNodeWithTag("drawer-tasks").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            hasTag("tasks-empty") || hasTag("task-list")
        }

        // Back to the list, then into Settings — a pushed destination, unlike the
        // Chats sub-screen above.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav-drawer-button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawer-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your identity key").assertIsDisplayed()
        composeRule.onNodeWithText("@$USERNAME").assertIsDisplayed()
    }

    private companion object {
        const val USERNAME = "ci_tester"
        const val PASSCODE = "comrade-ci-passcode"

        /**
         * How long to wait for the activity window to gain focus. Generous
         * because it is a cold-emulator warm-up, not app work — but bounded, so
         * a window that never focuses fails loudly instead of hanging.
         */
        const val FOCUS_TIMEOUT_MS = 20_000L
    }
}
