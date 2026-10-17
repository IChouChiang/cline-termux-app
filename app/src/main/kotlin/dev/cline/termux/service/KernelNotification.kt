package dev.cline.termux.service

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.cline.termux.R
import dev.cline.termux.data.notifications.HoldState
import dev.cline.termux.data.notifications.KeepAliveVariant
import dev.cline.termux.ui.kernel.quantityResources

/**
 * The "Cline is working..." notification for one [HoldState] (customize-and-rest.md §12.3). Pure
 * of side effects so every variant is testable; [KernelForegroundService] posts it as id 1.
 */
object KernelNotification {
    private const val MAX_INBOX_LINES = 5
    private const val TAG_TAP = "kernel"

    fun build(context: Context, state: HoldState): Notification {
        val variant = state.variant
        val builder = NotificationCompat.Builder(context, NotificationChannels.KERNEL)
            .setSmallIcon(R.drawable.ic_stat_cline)
            .setColor(NotificationChannels.accentColor(context))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val untitled = context.getString(R.string.notif_session_untitled)
        val reconnecting = context.getString(R.string.kernel_notification_reconnecting)
        val working = context.getString(R.string.kernel_notification_working)
        var private = true
        var stopFor: String? = null
        var disconnect = true
        when (variant) {
            is KeepAliveVariant.Working -> {
                builder.setContentTitle(working)
                    .setContentText(if (state.reconnecting) reconnecting else variant.title ?: untitled)
                    .setContentIntent(NotificationRouter.openSession(context, TAG_TAP, variant.sessionId))
                if (!state.reconnecting) {
                    builder.setUsesChronometer(true).setShowWhen(true).setWhen(variant.startedAt)
                    if (state.stopAvailable) stopFor = variant.sessionId
                } else {
                    builder.setShowWhen(false)
                }
            }
            is KeepAliveVariant.Sending -> {
                // No session yet: nothing to stop and nothing to open but Home.
                builder.setContentTitle(working)
                    .setContentText(if (state.reconnecting) reconnecting else variant.title ?: untitled)
                    .setContentIntent(NotificationRouter.openHome(context, TAG_TAP))
                if (!state.reconnecting) {
                    builder.setUsesChronometer(true).setShowWhen(true).setWhen(variant.startedAt)
                } else {
                    builder.setShowWhen(false)
                }
            }
            is KeepAliveVariant.WaitingApproval -> {
                val tool = variant.toolName.ifBlank { context.getString(R.string.notif_approval_tool_fallback) }
                builder.setContentTitle(context.getString(R.string.kernel_notification_waiting))
                    .setContentText(if (state.reconnecting) reconnecting else context.getString(R.string.kernel_notification_waiting_text, tool))
                    .setShowWhen(false)
                    .setContentIntent(NotificationRouter.openSession(context, TAG_TAP, variant.sessionId, NotificationRouter.Sheet.Approval))
            }
            is KeepAliveVariant.WaitingAnswer -> {
                builder.setContentTitle(context.getString(R.string.kernel_notification_question))
                    .setContentText(if (state.reconnecting) reconnecting else variant.title ?: untitled)
                    .setShowWhen(false)
                    .setContentIntent(NotificationRouter.openSession(context, TAG_TAP, variant.sessionId, NotificationRouter.Sheet.Question))
            }
            is KeepAliveVariant.Several -> {
                val names = variant.titles.map { it ?: untitled }
                val title = quantityResources(context.resources).getQuantityString(R.plurals.kernel_notification_several, variant.count, variant.count)
                val joined = names.joinToString(context.getString(R.string.kernel_notification_title_separator))
                builder.setContentTitle(title)
                    .setContentText(if (state.reconnecting) reconnecting else joined)
                    .setShowWhen(false)
                    .setContentIntent(NotificationRouter.openHome(context, TAG_TAP))
                if (!state.reconnecting) {
                    builder.setStyle(
                        NotificationCompat.InboxStyle().setBigContentTitle(title).also { inbox ->
                            names.take(MAX_INBOX_LINES).forEach(inbox::addLine)
                        },
                    )
                }
            }
            is KeepAliveVariant.Stopping -> {
                builder.setContentTitle(context.getString(R.string.kernel_notification_stopping))
                    .setContentText(if (state.reconnecting) reconnecting else variant.title ?: untitled)
                    .setShowWhen(false)
                    .setContentIntent(NotificationRouter.openSession(context, TAG_TAP, variant.sessionId))
                disconnect = false
            }
            KeepAliveVariant.ConnectedIdle, KeepAliveVariant.None -> {
                builder.setContentTitle(context.getString(R.string.kernel_notification_connected))
                    .setContentText(if (state.reconnecting) reconnecting else context.getString(R.string.kernel_notification_connected_text))
                    .setShowWhen(false)
                    .setContentIntent(NotificationRouter.openHome(context, TAG_TAP))
                private = false
            }
        }
        stopFor?.let { sessionId ->
            builder.addAction(0, context.getString(R.string.kernel_notification_action_stop), KernelServiceActions.stopAgent(context, sessionId))
        }
        if (disconnect) {
            builder.addAction(0, context.getString(R.string.kernel_notification_action_disconnect), KernelServiceActions.disconnect(context))
        }
        if (private) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, NotificationChannels.KERNEL)
                        .setSmallIcon(R.drawable.ic_stat_cline)
                        .setColor(NotificationChannels.accentColor(context))
                        .setContentTitle(working)
                        .build(),
                )
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
        return builder.build()
    }
}
