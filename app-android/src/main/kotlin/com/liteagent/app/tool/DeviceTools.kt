package com.liteagent.app.tool

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 读剪贴板。
 *
 * Android 10+ 起，后台应用读剪贴板会被系统直接返回 null——这不是权限问题，是硬限制。
 * 我们的调用场景是用户在前台跟助理说话，所以可用；但如果是"每日简报"想在后台定时触发，
 * 就必须改成前台通知点击后再读。这一点写在这里，避免以后想当然地加静默定时。
 */
class ClipboardTool(private val context: Context) {

    fun read(): String {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return "读取失败：剪贴板服务不可用"

        val clip = manager.primaryClip ?: return "剪贴板是空的"
        if (clip.itemCount == 0) return "剪贴板是空的"

        val text = runCatching {
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        }.getOrElse { return "读取失败：${it.javaClass.simpleName}" }

        if (text.isBlank()) return "剪贴板里没有文字内容"
        // 截断：剪贴板可能有几千字，全塞进 prompt 会把上下文吃光
        val preview = if (text.length > 800) text.take(800) + "…（已截断）" else text
        return "剪贴板内容（共 ${text.length} 字）：\n$preview"
    }
}

/**
 * 读今日日程。
 *
 * 这是只读操作，比"写日历"低风险得多，但仍然需要 READ_CALENDAR 运行时权限。
 * 没授权时不假装成功——返回一句能直接转述给用户的话。
 */
class CalendarTool(private val context: Context) {

    fun listToday(): String {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "无法读取日程：应用还没有日历读取权限，请在系统设置里授予"

        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val begin = dayStart.timeInMillis
        val end = begin + 24 * 60 * 60 * 1000L

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString())
            .appendPath(end.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END
        )

        return runCatching {
            val items = mutableListOf<String>()
            context.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIdx) ?: "（无标题）"
                    val start = formatTime(cursor.getLong(beginIdx))
                    val finish = formatTime(cursor.getLong(endIdx))
                    items += "$start-$finish $title"
                }
            }
            when {
                items.isEmpty() -> "今天没有日程安排"
                items.size > 12 -> "今天共有 ${items.size} 项日程，前 12 项：\n" +
                    items.take(12).joinToString("\n")
                else -> "今天有 ${items.size} 项日程：\n" + items.joinToString("\n")
            }
        }.getOrElse { t ->
            if (t is SecurityException) "无法读取日程：权限被拒绝"
            else "读取日程失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
}
