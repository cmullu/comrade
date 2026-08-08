package mullu.comrade.together

import android.service.notification.NotificationListenerService

/**
 * The grant, and nothing else.
 *
 * `MediaSessionManager.getActiveSessions` will only answer a caller that names
 * the component of an **enabled notification listener**, which is the single
 * reason this class exists. `docs/TOGETHER.md` §13.
 *
 * **It overrides nothing on purpose.** Comrade does not read notifications,
 * does not keep them, and does not post through this — an empty body is a
 * stronger statement of that than a comment on a class that had methods. The
 * permission is being used for the media-session API it gates and for nothing
 * else, and anyone adding an `onNotificationPosted` here is changing what this
 * app claims about itself, not adding a feature.
 *
 * A top-level class rather than a member of [MediaSessionAccess] because the
 * manifest names it: a nested one would be `MediaSessionAccess$Listener` in the
 * XML, which is legal, obscure, and breaks silently if the outer type is ever
 * renamed or converted.
 */
class MediaSessionListenerService : NotificationListenerService()
