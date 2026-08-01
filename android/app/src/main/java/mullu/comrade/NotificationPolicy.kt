package mullu.comrade

/**
 * When a notification is warranted. Pure decisions, no Android types, so
 * `NotificationPolicyTest` can pin them — the rules used to be `if` conditions
 * inlined in [ChatEventRouter.route], where "does mute apply to attachments
 * too?" could only be answered by reading two call sites and hoping they
 * matched.
 */
object NotificationPolicy {

    /**
     * A DM or attachment from [peer].
     *
     * Two things suppress it:
     * - the conversation is **on screen** — the message is already visible, and
     *   buzzing about it is how an app tells you what you are looking at;
     * - the user **muted** that conversation.
     *
     * Mute wins even when the thread is not on screen, and applies to
     * attachments exactly as to text: "mute this chat" means the chat, not the
     * text half of it.
     */
    fun shouldNotifyMessage(peer: String, openConversationPeer: String?, muted: Boolean): Boolean {
        if (muted) return false
        return peer != openConversationPeer
    }

    /**
     * A comrade coming online.
     *
     * Muting a conversation silences their presence notice too: someone who
     * asked not to be buzzed about a person meant the person, not one class of
     * event about them. The dot in the chat list stays — mute is about
     * interruption, not about hiding information from the app itself.
     */
    fun shouldNotifyPresence(
        peer: String,
        openConversationPeer: String?,
        muted: Boolean,
        becameOnline: Boolean,
    ): Boolean {
        if (!becameOnline) return false
        if (muted) return false
        return peer != openConversationPeer
    }

    /**
     * A comrade who wrote something and never sent it (`comrade_core::nudge`).
     *
     * Same two suppressors as everything else about a person — mute, and having
     * their conversation on screen. The on-screen case is worth being explicit
     * about: a nudge is *actionable in that thread*, so someone already looking
     * at it has already arrived where the notification would have sent them.
     *
     * There is no "edge" parameter to match [shouldNotifyPresence]'s
     * `becameOnline`. A nudge is not state that can repeat: the sender emits one
     * per hesitation and holds a cooldown, and the core drops replays, so every
     * event that reaches here is news by construction.
     */
    fun shouldNotifyNudge(peer: String, openConversationPeer: String?, muted: Boolean): Boolean {
        if (muted) return false
        return peer != openConversationPeer
    }

    // Incoming calls deliberately have no rule here: mute never silences a
    // ringing call. A muted conversation is a preference about chatter, and
    // dropping a call would lose the user something they cannot get back —
    // Telegram draws the same line (its mute has a separate "mute calls").

    /** Whether the message group's summary is now orphaned (no children left). */
    fun summaryStale(messageChildren: Int): Boolean = messageChildren <= 0
}
