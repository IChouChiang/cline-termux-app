package dev.cline.termux.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.cline.termux.R
import dev.cline.termux.data.notifications.NotificationEventType

/**
 * Notification channels (settings-providers.md S3.3, customize-and-rest.md §11.7, §12.1). Ids
 * never change after this release. The Sound toggle picks between a sounding channel and its
 * `_quiet` twin; `setSilent` is never used for session notifications (a silent notification
 * loses its heads-up peek).
 */
object NotificationChannels {
    const val KERNEL = "kernel"
    const val ACCOUNT = "account"
    const val GROUP_SESSIONS = "session_events"

    /** Channels of the build before this one; nothing was ever posted on them (§11.7 step 3). */
    private val LEGACY = listOf("sessions", "attention")

    /** One event's two channels (S3.3 table). */
    enum class SessionChannel(
        val type: NotificationEventType,
        val sounding: String,
        val quiet: String,
        val importance: Int,
        val quietVibrates: Boolean,
        private val soundingName: Int,
        private val quietName: Int,
    ) {
        TaskCompleted(
            NotificationEventType.TaskCompletion, "task_completed", "task_completed_quiet",
            NotificationManagerCompat.IMPORTANCE_DEFAULT, quietVibrates = false,
            R.string.notif_channel_task_completed, R.string.notif_channel_task_completed_quiet,
        ),
        ApprovalNeeded(
            NotificationEventType.ApprovalNeeded, "approval_needed", "approval_needed_quiet",
            NotificationManagerCompat.IMPORTANCE_HIGH, quietVibrates = true,
            R.string.notif_channel_approval_needed, R.string.notif_channel_approval_needed_quiet,
        ),
        QuestionAsked(
            NotificationEventType.QuestionAsked, "question_asked", "question_asked_quiet",
            NotificationManagerCompat.IMPORTANCE_HIGH, quietVibrates = true,
            R.string.notif_channel_question_asked, R.string.notif_channel_question_asked_quiet,
        ),
        SessionError(
            NotificationEventType.SessionError, "session_error", "session_error_quiet",
            NotificationManagerCompat.IMPORTANCE_DEFAULT, quietVibrates = false,
            R.string.notif_channel_session_error, R.string.notif_channel_session_error_quiet,
        ),
        ;

        fun id(sound: Boolean): String = if (sound) sounding else quiet

        internal fun channels(context: Context): List<NotificationChannelCompat> = listOf(
            NotificationChannelCompat.Builder(sounding, importance)
                .setName(context.getString(soundingName))
                .setGroup(GROUP_SESSIONS)
                .setVibrationEnabled(true)
                .build(),
            NotificationChannelCompat.Builder(quiet, importance)
                .setName(context.getString(quietName))
                .setGroup(GROUP_SESSIONS)
                .setSound(null, null)
                .setVibrationEnabled(quietVibrates)
                .build(),
        )

        companion object {
            fun of(type: NotificationEventType): SessionChannel = entries.first { it.type == type }
        }
    }

    /** Every session channel id (eight). */
    val sessionChannelIds: List<String> = SessionChannel.entries.flatMap { listOf(it.sounding, it.quiet) }

    /** The channel an event posts on with its Sound preference. */
    fun channelFor(type: NotificationEventType, sound: Boolean): String = SessionChannel.of(type).id(sound)

    /** Creates (or renames on a locale change) every channel and deletes the legacy ones. Idempotent. */
    fun ensure(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelGroup(
            NotificationChannelGroupCompat.Builder(GROUP_SESSIONS).setName(context.getString(R.string.notif_channel_group_sessions)).build(),
        )
        val channels = buildList {
            add(
                NotificationChannelCompat.Builder(KERNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.notif_channel_kernel))
                    .setDescription(context.getString(R.string.notif_channel_kernel_description))
                    .setShowBadge(false)
                    .build(),
            )
            add(
                NotificationChannelCompat.Builder(ACCOUNT, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.notif_channel_account))
                    .build(),
            )
            SessionChannel.entries.forEach { addAll(it.channels(context)) }
        }
        manager.createNotificationChannelsCompat(channels)
        LEGACY.forEach(manager::deleteNotificationChannel)
    }

    /** The user blocked [channelId] in system settings (S3.2 "Blocked in system settings"). */
    fun isBlocked(context: Context, channelId: String): Boolean =
        NotificationManagerCompat.from(context).getNotificationChannelCompat(channelId)?.importance == NotificationManagerCompat.IMPORTANCE_NONE

    /** App-level notifications on and, on API 33+, `POST_NOTIFICATIONS` granted. */
    fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** [canPost] and the channel is not blocked. */
    fun canPostOn(context: Context, channelId: String): Boolean = canPost(context) && !isBlocked(context, channelId)

    /**
     * Design-system §1.7 "Notification accent": the device palette's accent1 600 / 200 on API 31+
     * (the voice app's `Tone.notificationColor`), else the brand fallback; [error] uses the
     * fixed error pair. Light or dark follows the current night mode, as the shade does.
     */
    @ColorInt
    fun accentColor(context: Context, error: Boolean = false): Int {
        if (error) return ContextCompat.getColor(context, R.color.notification_error)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return context.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        }
        return ContextCompat.getColor(context, R.color.notification_accent)
    }

    internal fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)
}
