package mullu.comrade

/**
 * What to do about an incoming event: interrupt, post quietly, or say nothing.
 *
 * The middle case is the one that had been missing. Every rule here used to
 * return a `Boolean`, which forced quiet hours to be spelled as "no
 * notification" — and "no notification" for a *message* does not mean the user
 * reads it at 08:00 instead of 01:00, it means the message never appears in the
 * shade at all and they find it only by opening the app. That is indistinguishable
 * from a delivery failure, and it is what quiet hours were doing every night.
 */
enum class NotifyDecision {
    /** Post normally — sound, vibration, heads-up if the channel allows. */
    Alert,

    /** Post, but wake nobody: it lands in the shade to be found later. */
    Silent,

    /** Post nothing. Reserved for cases where a notification would be wrong, not merely loud. */
    Suppress,
}

/**
 * When a notification is warranted. Pure decisions, no Android types, so
 * `NotificationPolicyTest` can pin them — the rules used to be `if` conditions
 * inlined in [ChatEventRouter.route], where "does mute apply to attachments
 * too?" could only be answered by reading two call sites and hoping they
 * matched.
 *
 * ## The line quiet hours draw
 *
 * A quiet hour silences; it does not discard. The split is by whether the thing
 * will still be true in the morning:
 *
 *  - a **message**, a **message request**, an **update notice** all keep — they
 *    are posted [NotifyDecision.Silent] and read whenever the user wakes up;
 *  - **presence** and a **nudge** do not — both describe a person who is around
 *    *right now* and both expire in minutes, so a silent one found at 08:00
 *    would be a claim about last night. Those are [NotifyDecision.Suppress]ed,
 *    losing nothing that was still true.
 *
 * A ringing call is not routed through here at all (see the note at the bottom).
 */
object NotificationPolicy {

    /**
     * A DM or attachment from [peer].
     *
     * Three inputs, and they are not interchangeable:
     * - **mute** is the user saying "never interrupt me about this conversation"
     *   → [NotifyDecision.Suppress]. It applies to attachments exactly as to
     *   text: "mute this chat" means the chat, not the text half of it.
     * - **the conversation is on screen**, meaning the app is visible *and* this
     *   is the thread being looked at → [NotifyDecision.Suppress]: the message
     *   is already in front of them, and buzzing about it is how an app tells
     *   you what you are looking at.
     * - **quiet hours** → [NotifyDecision.Silent], never [NotifyDecision.Suppress].
     *
     * [appVisible] is what makes the second rule honest, and its absence was a
     * bug with a very specific shape: the open-conversation peer is set when the
     * user navigates into a thread and cleared when they navigate out —
     * backgrounding the app does neither. So pressing Home while a chat was open
     * left that peer marked "on screen" indefinitely, and the one person the
     * user was in the middle of talking to became the one person whose messages
     * never notified. Visibility is tracked separately (see
     * [ChatEventRouter.setAppVisible]) precisely so "on screen" can mean it.
     */
    fun messageDecision(
        peer: String,
        openConversationPeer: String?,
        appVisible: Boolean,
        muted: Boolean,
        quietHours: Boolean = false,
    ): NotifyDecision = when {
        muted -> NotifyDecision.Suppress
        onScreen(peer, openConversationPeer, appVisible) -> NotifyDecision.Suppress
        // Silent, not suppressed: the message keeps until morning, so silencing
        // it costs the user nothing and dropping it costs them the message.
        quietHours -> NotifyDecision.Silent
        else -> NotifyDecision.Alert
    }

    /**
     * A comrade coming online.
     *
     * Muting a conversation silences their presence notice too: someone who
     * asked not to be buzzed about a person meant the person, not one class of
     * event about them. The dot in the chat list stays — mute is about
     * interruption, not about hiding information from the app itself.
     *
     * Quiet hours [NotifyDecision.Suppress] rather than silence this one, unlike
     * a message: "Ana is around" is only ever true for the next few minutes (the
     * notice carries its own timeout for the same reason), so there is no quiet
     * version of it worth finding in the morning.
     */
    fun presenceDecision(
        peer: String,
        openConversationPeer: String?,
        appVisible: Boolean,
        muted: Boolean,
        becameOnline: Boolean,
        quietHours: Boolean = false,
    ): NotifyDecision = when {
        !becameOnline -> NotifyDecision.Suppress
        muted -> NotifyDecision.Suppress
        quietHours -> NotifyDecision.Suppress
        onScreen(peer, openConversationPeer, appVisible) -> NotifyDecision.Suppress
        else -> NotifyDecision.Alert
    }

    /**
     * A message request from a stranger. Quiet hours silence it; nothing else
     * applies (a request has no conversation to be "on screen", and mute is per
     * accepted conversation).
     */
    fun requestDecision(quietHours: Boolean = false): NotifyDecision =
        if (quietHours) NotifyDecision.Silent else NotifyDecision.Alert

    /**
     * An "a newer Comrade shipped" notice. Silent during quiet hours — a release
     * can always wait until morning, and unlike presence it is still true then.
     */
    fun updateDecision(quietHours: Boolean = false): NotifyDecision =
        if (quietHours) NotifyDecision.Silent else NotifyDecision.Alert

    /**
     * A comrade who wrote something and never sent it (`comrade_core::nudge`).
     *
     * The same suppressors as everything else about a person — mute, having
     * their conversation on screen, and the nightly quiet window. The on-screen
     * case is worth being explicit about: a nudge is *actionable in that
     * thread*, so someone already looking at it has already arrived where the
     * notification would have sent them.
     *
     * Quiet hours suppress rather than silence, for [presenceDecision]'s reason:
     * a nudge's TTL matches presence's, so a quiet one would be stale by the
     * time it was seen.
     *
     * There is no "edge" parameter to match [presenceDecision]'s `becameOnline`.
     * A nudge is not state that can repeat: the sender emits one per hesitation
     * and holds a cooldown, and the core drops replays, so every event that
     * reaches here is news by construction.
     */
    fun nudgeDecision(
        peer: String,
        openConversationPeer: String?,
        appVisible: Boolean,
        muted: Boolean,
        quietHours: Boolean = false,
    ): NotifyDecision = when {
        muted -> NotifyDecision.Suppress
        quietHours -> NotifyDecision.Suppress
        onScreen(peer, openConversationPeer, appVisible) -> NotifyDecision.Suppress
        else -> NotifyDecision.Alert
    }

    /**
     * Whether [peer]'s conversation is genuinely in front of the user: it is the
     * open thread **and** the app is visible. Either half alone is not enough —
     * see [messageDecision] for what the missing visibility check cost.
     */
    private fun onScreen(peer: String, openConversationPeer: String?, appVisible: Boolean): Boolean =
        appVisible && peer == openConversationPeer

    // Incoming calls deliberately have no rule here: neither mute nor quiet
    // hours silences a ringing call. A muted conversation is a preference about
    // chatter, and quiet hours are about sleep — but dropping a call would lose
    // the user something they cannot get back, and someone ringing at 3am may
    // be exactly the person this app exists for. Telegram draws the same line
    // (its mute has a separate "mute calls"), and `docs/ATTENTION.md` states it
    // as a rule: attention features never come between a user and a call.

    /** Whether the message group's summary is now orphaned (no children left). */
    fun summaryStale(messageChildren: Int): Boolean = messageChildren <= 0
}
