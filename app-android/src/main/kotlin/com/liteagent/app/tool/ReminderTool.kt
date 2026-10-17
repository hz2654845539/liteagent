package com.liteagent.app.tool

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置提醒。
 *
 * Android 上的真实约束（不写进代码就会在验收时才发现"以为通了实际被拦"）：
 * - API 31+ 精确闹钟需要 SCHEDULE_EXACT_ALARM，且用户可随时撤销；
 *   `USE_EXACT_ALARM` 是闹钟类应用专用的，普通应用上架会被打回。
 *   拿不到精确权限时降级成 setAndAllowWhileIdle（可能延迟几分钟），并把这件事告诉模型。
 * - API 33+ 通知需要运行时权限 POST_NOTIFICATIONS。
 */
class ReminderTool(private val context: Context) {

    fun setReminder(content: String, time: String): Result<String> = runCatching {
        require(content.isNotBlank()) { "提醒内容为空" }
        val triggerAt = parseTime(time)
        val now = System.currentTimeMillis()
        require(triggerAt > now) { "提醒时间已经过去：$time" }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: error("设备不支持闹钟服务")

        val requestCode = (content + time).hashCode()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_CONTENT, content)
            putExtra(ReminderReceiver.EXTRA_ID, requestCode)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            "已设置提醒「$content」，将在 $time 准时触发"
        } else {
            // 没有精确闹钟权限：不假装成功，明确告诉模型会延迟
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            "已设置提醒「$content」，时间 $time。注意：系统未授予精确闹钟权限，实际触发可能延迟几分钟"
        }
    }

    private fun parseTime(raw: String): Long {
        val text = raw.trim().replace('T', ' ')
        for (pattern in FORMATS) {
            val result = runCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
            if (result != null) return result
        }
        error("时间格式无法解析：$raw（需要 yyyy-MM-dd HH:mm）")
    }

    private companion object {
        val FORMATS = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm")
    }
}

/** 闹钟到点后弹通知。做成独立 Receiver，进程被回收也能收到。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val content = intent.getStringExtra(EXTRA_CONTENT) ?: return
        val id = intent.getIntExtra(EXTRA_ID, content.hashCode())

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "提醒", NotificationManager.IMPORTANCE_HIGH)
        )

        // 没有通知权限时不能静默失败，至少打一条日志便于排查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("ReminderReceiver", "缺少 POST_NOTIFICATIONS 权限，通知无法展示")
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LiteAgent 提醒")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(id, notification)
    }

    companion object {
        const val EXTRA_CONTENT = "extra_content"
        const val EXTRA_ID = "extra_id"
        private const val CHANNEL_ID = "liteagent_reminder"
    }
}

/** 工具分发。新增工具只需在这里加一个分支，不影响生成链路。 */
class ToolRouter(private val context: Context) {

    private val reminder = ReminderTool(context)
    private val clipboard = ClipboardTool(context)
    private val calendar = CalendarTool(context)

    fun execute(call: RawToolCall): String = when (call.tool) {
        ToolSpec.SET_REMINDER -> {
            val content = call.args["content"]
            val time = call.args["time"]
            when {
                content.isNullOrBlank() -> "调用失败：缺少 content 参数"
                time.isNullOrBlank() -> "调用失败：缺少 time 参数"
                else -> reminder.setReminder(content, time).fold(
                    onSuccess = { it },
                    onFailure = { "调用失败：${it.message}" }
                )
            }
        }

        ToolSpec.READ_CLIPBOARD -> clipboard.read()

        ToolSpec.LIST_TODAY_EVENTS -> calendar.listToday()

        else -> "调用失败：未知工具 ${call.tool}"
    }
}
