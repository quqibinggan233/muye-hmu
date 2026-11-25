package com.hmu.muye.data.parcel

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fengkao.timetable.TimetableApp

/**
 * 通知栏快递取件码监听服务：
 * 仅在本地过滤短信、微信及主流快递/电商通知中的取件码并落盘，绝不上传或外发任何通知文本。
 */
class ParcelNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return
        if (!isCandidatePackage(pkg)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $text".trim()
        if (combined.isBlank()) return

        val app = applicationContext as? TimetableApp ?: return
        app.container.parcels.ingestText(combined, source = "通知栏")
    }

    private fun isCandidatePackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return CANDIDATE_HINTS.any { lower.contains(it) }
    }

    companion object {
        private val CANDIDATE_HINTS = listOf(
            "mms", "messaging", "sms",
            "tencent.mm",
            "cainiao", "taobao", "pinduoduo", "jingdong", "jd.", "sfexpress", "fcbox",
        )

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            val target = ComponentName(context, ParcelNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it.equals(target, ignoreCase = true) }
        }

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
