package com.softwaremine.dps.data.android.tool

import com.softwaremine.dps.data.android.common.ToolArguments
import com.softwaremine.dps.data.android.notification.DpsNotificationChannel
import com.softwaremine.dps.data.android.notification.NotificationPresenter
import com.softwaremine.dps.domain.permission.DpsPermission
import com.softwaremine.dps.domain.tool.AndroidTool
import com.softwaremine.dps.domain.tool.ToolCall
import com.softwaremine.dps.domain.tool.ToolId
import com.softwaremine.dps.domain.tool.ToolResult
import kotlin.random.Random

/**
 * Posts and cancels notifications.
 *
 * ## Purpose
 * The first real Android capability. Replaces the Phase A
 * [UnimplementedTool] registration for [ToolId.NOTIFICATION].
 *
 * ## Operations
 * | Operation | Arguments | Result |
 * |---|---|---|
 * | `notify` | `title` (required), `body`, `channel`, `priority`, `auto_cancel`, `id` | [ToolResult.Success] with the id |
 * | `cancel` | `id` (required) | [ToolResult.Success] |
 *
 * ## Permission
 * Declares [DpsPermission.POST_NOTIFICATIONS], which the executor checks before
 * dispatch. On API 32 and below that permission does not exist and resolves to
 * `NOT_REQUIRED`, so this tool runs unguarded there — correctly, since the
 * platform imposes no gate.
 *
 * A second, separate condition is handled here rather than by the executor: the
 * user can disable notifications for the app while the permission remains
 * granted. `notify()` would then do nothing and report nothing. That is
 * detected and returned as [ToolResult.Failure] — a reminder that silently
 * never appears is the single worst outcome for this product.
 *
 * ## Why the presenter is injected
 * [ReminderReceiver][com.softwaremine.dps.data.android.reminder.ReminderReceiver]
 * posts notifications too. Sharing [NotificationPresenter] means reminder and
 * assistant notifications cannot drift apart in icon, channel handling or
 * PendingIntent flags.
 *
 * ## Dependencies
 * [NotificationPresenter], domain tool types. No direct Android imports — the
 * platform work is entirely inside the presenter.
 */
class AndroidNotificationTool(
    private val presenter: NotificationPresenter,
) : AndroidTool {

    override val id: ToolId = ToolId.NOTIFICATION

    override val operations: Set<String> = setOf(OP_NOTIFY, OP_CANCEL)

    override val requiredPermissions: Set<DpsPermission> =
        setOf(DpsPermission.POST_NOTIFICATIONS)

    override suspend fun execute(call: ToolCall): ToolResult = when (call.operation) {
        OP_NOTIFY -> notify(call)
        OP_CANCEL -> cancel(call)
        // Unreachable: the executor checks supports() first. Present so the
        // when is exhaustive and a future operation added to `operations`
        // without an implementation fails loudly rather than silently.
        else -> ToolResult.Unsupported("'${call.operation}' is not implemented.")
    }

    private fun notify(call: ToolCall): ToolResult {
        val title = when (val parsed = ToolArguments.required(call, ARG_TITLE)) {
            is ToolArguments.Parsed.Invalid -> return parsed.failure
            is ToolArguments.Parsed.Value -> parsed.value
        }

        val body = call.argumentOr(ARG_BODY, "")
        val channel = DpsNotificationChannel.fromName(call.argument(ARG_CHANNEL))
        val autoCancel = ToolArguments.boolean(call, ARG_AUTO_CANCEL, default = true)

        // A caller-supplied id lets a notification be updated or cancelled
        // later; otherwise a random one avoids silently replacing an unrelated
        // notification that happened to share an id.
        val notificationId = ToolArguments.int(call, ARG_ID, default = Random.nextInt(1, Int.MAX_VALUE))

        val posted = presenter.post(
            id = notificationId,
            channel = channel,
            title = title,
            body = body,
            autoCancel = autoCancel,
        )

        return if (posted) {
            ToolResult.Success(
                summary = "Notification shown.",
                data = mapOf(
                    "notification_id" to notificationId.toString(),
                    "channel" to channel.id,
                ),
            )
        } else {
            ToolResult.Failure(
                reason = "Notifications are turned off for DPS in system settings.",
                // Retryable once the user re-enables them; not a defect.
                retryable = true,
            )
        }
    }

    private fun cancel(call: ToolCall): ToolResult {
        val rawId = call.argument(ARG_ID)
            ?: return ToolResult.Failure("Missing required argument 'id'.", retryable = false)

        val notificationId = rawId.trim().toIntOrNull()
            ?: return ToolResult.Failure("'id' must be a number.", retryable = false)

        presenter.cancel(notificationId)

        // Deliberately reported as success even when nothing was showing.
        // Android provides no way to tell, and "cancel a notification that is
        // already gone" has achieved the caller's intent either way.
        return ToolResult.Success(
            summary = "Notification dismissed.",
            data = mapOf("notification_id" to notificationId.toString()),
        )
    }

    private companion object {
        const val OP_NOTIFY = "notify"
        const val OP_CANCEL = "cancel"

        const val ARG_TITLE = "title"
        const val ARG_BODY = "body"
        const val ARG_CHANNEL = "channel"
        const val ARG_AUTO_CANCEL = "auto_cancel"
        const val ARG_ID = "id"
    }
}
