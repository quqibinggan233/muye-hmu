package com.hmu.muye.notify

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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fengkao.timetable.MainActivity
import com.fengkao.timetable.R
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.local.SettingsStore
import com.fengkao.timetable.data.local.ScheduleCache
import com.hmu.muye.data.campus.CampusLifeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 课程上课提醒闹钟：支持「课前 N 分钟提醒」与「上课时刻提醒」，按本机课表调度最近一次提醒。
 */
object ClassReminderScheduler {

    const val CHANNEL_ID = "class_reminders"
    const val ACTION_TICK = "com.hmu.muye.CLASS_REMINDER_TICK"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_BODY = "body"
    private const val RC_ALARM = 8401
    private const val NOTIFY_ID = 8402
    private const val RC_OPEN = 8403

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val baseIntent = Intent(app, ClassReminderReceiver::class.java).setAction(ACTION_TICK)
        val existingPi = PendingIntent.getBroadcast(
            app,
            RC_ALARM,
            baseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(existingPi)

        val prefs = CampusLifeStore(app).classReminders()
        if (!prefs.enabled) return

        val settings = runBlocking { SettingsStore(app).first() }
        val cached = ScheduleCache(app).load() ?: return
        val courses = (cached.courses + settings.customCourses.map { it.toCourseBlock() })
            .filter { it.stableKey !in settings.hiddenCourses }
        if (courses.isEmpty()) return

        val term = com.fengkao.timetable.data.TermSpec(cached.xnm, cached.term)
        val termStart = settings.termStartFor(term)
        val pts = settings.periodTimes()
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()

        var bestMillis: Long? = null
        var bestTitle = ""
        var bestBody = ""

        for (dayOffset in 0L..6L) {
            val day: LocalDate = TermCalculator.today().plusDays(dayOffset)
            val week = TermCalculator.weekOf(termStart, day)
            if (week !in 1..settings.termWeeks) continue
            val dayCourses = courses.filter { it.dayOfWeek == day.dayOfWeek.value && week in it.weeks }
            for (course in dayCourses) {
                val p = pts.getOrNull(course.startPeriod - 1) ?: continue
                val classStart = LocalDateTime.of(day.year, day.month, day.dayOfMonth, p.startHour, p.startMinute)
                val triggers = buildList {
                    if (prefs.beforeMinutes > 0) {
                        add(
                            classStart.minusMinutes(prefs.beforeMinutes.toLong()) to
                                ("还有 ${prefs.beforeMinutes} 分钟上课 · ${course.name}" to
                                    "${p.startText()} · ${course.place.ifBlank { "教室待定" }}"),
                        )
                    }
                    if (prefs.atClassStart) {
                        add(
                            classStart to
                                ("现在上课 · ${course.name}" to
                                    "${p.startText()}–${pts.getOrNull(course.endPeriod - 1)?.endText() ?: ""} · ${course.place.ifBlank { "教室待定" }}"),
                        )
                    }
                }
                for ((triggerDt, pair) in triggers) {
                    if (triggerDt.isAfter(now)) {
                        val ms = triggerDt.atZone(zone).toInstant().toEpochMilli()
                        if (bestMillis == null || ms < bestMillis) {
                            bestMillis = ms
                            bestTitle = pair.first
                            bestBody = pair.second
                        }
                    }
                }
            }
        }

        val targetMs = bestMillis ?: return
        val fireIntent = Intent(app, ClassReminderReceiver::class.java).apply {
            action = ACTION_TICK
            putExtra(EXTRA_TITLE, bestTitle)
            putExtra(EXTRA_BODY, bestBody)
        }
        val pi = PendingIntent.getBroadcast(
            app,
            RC_ALARM,
            fireIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pi)
        }
    }

    internal fun showNotification(context: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            RC_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notice)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "课前与上课时刻自动通知"
        }
        nm.createNotificationChannel(ch)
    }
}

class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ClassReminderScheduler.ACTION_TICK) {
            val title = intent.getStringExtra("title").orEmpty()
            val body = intent.getStringExtra("body").orEmpty()
            if (title.isNotBlank()) {
                ClassReminderScheduler.showNotification(context, title, body)
            }
        }
        ClassReminderScheduler.reschedule(context)
    }
}
