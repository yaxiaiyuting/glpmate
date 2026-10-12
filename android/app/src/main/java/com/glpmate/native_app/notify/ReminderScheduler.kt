package com.glpmate.native_app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import java.util.Calendar

/**
 * 用药/生活提醒的本地调度。
 *
 * 设计取舍：
 *  - 用 AlarmManager 而不是 WorkManager：提醒是"每天固定钟点"，不需要 JobScheduler
 *    那套网络/充电约束；也少一个依赖。代价是重启后要自己重排（见 BootReceiver）。
 *  - 精确闹钟：用药提醒差十几分钟就失去意义，所以申请 SCHEDULE_EXACT_ALARM
 *    （API 31+ 用户可能手动关掉，这里做降级处理：拿不到精确权限就用非精确，
 *    并如实告诉用户可能不准）。
 *  - 提醒时间来源是**后端的 schedule**，但闹钟本身不能依赖后端在跑
 *    （后端在我们自己进程里，进程被杀了就没了）。所以把时间表缓存到
 *    SharedPreferences，闹钟只读缓存。
 *
 * 通知里绝不放任何医疗建议，只写"该记录 X 了"。
 */
object ReminderScheduler {

    private const val TAG = "GlpReminder"
    private const val PREFS = "glpmate_reminders"
    private const val KEY_SCHEDULE = "schedule_json"

    /** 把后端下发的 schedule 存进缓存并重排闹钟。 */
    fun syncFromSchedule(context: Context, schedule: JSONArray?) {
        if (schedule == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCHEDULE, schedule.toString())
            .apply()
        reschedule(context)
    }

    /** 按缓存的时间表重排所有闹钟（启动时、重启后都调它）。 */
    fun reschedule(context: Context) {
        val items = cachedSchedule(context)
        if (items.isEmpty()) {
            Log.i(TAG, "没有提醒计划，跳过（用户没设置或还没同步过后端）")
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.w(TAG, "拿不到 AlarmManager")
            return
        }

        var scheduled = 0
        var inexact = 0
        items.forEachIndexed { index, item ->
            if (!item.enabled) {
                cancel(context, index)
                return@forEachIndexed
            }
            val trigger = nextTrigger(item.start)
            val pending = pendingIntent(context, index, item.key, item.label)
            // 先试精确闹钟，被系统拒绝再降级成非精确。
            //
            // 为什么不能只看 canScheduleExactAlarms()：那个 API 只反映
            // SCHEDULE_EXACT_ALARM（Android 14+ 默认**不授予**），而我们在清单里
            // 同时声明了 USE_EXACT_ALARM（自动授予）。实测在 Android 16 上
            // canScheduleExactAlarms() 返回 false，但 setExactAndAllowWhileIdle
            // 其实是被允许的——只看那个 API 会白白退化成 1 小时窗口，
            // 用户设的"8:00 喝水"可能 8:47 才响。
            // 所以策略是：**先试精确，抛 SecurityException 才降级**。
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, trigger, pending,
                )
                scheduled += 1
                Log.i(TAG, "已排精确提醒：${item.label} @ ${item.start}")
            } catch (e: SecurityException) {
                Log.w(TAG, "精确闹钟被拒（${e.message}），降级为非精确：${item.label}")
                try {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, trigger, pending,
                    )
                    scheduled += 1
                    inexact += 1
                } catch (e2: SecurityException) {
                    Log.w(TAG, "排 ${item.label} 失败：${e2.message}")
                }
            }
        }
        Log.i(
            TAG,
            "已排 $scheduled 个提醒" +
                if (inexact > 0) "（其中 $inexact 个只能非精确，时间可能偏最多 1 小时）" else "（全部精确）",
        )
    }

    fun cancelAll(context: Context) {
        cachedSchedule(context).forEachIndexed { index, _ -> cancel(context, index) }
    }

    private fun cancel(context: Context, index: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context, index, "cancel", "cancel"))
    }

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /**
     * 把 "HH:mm" 换算成"下一个该响的时刻"。
     * 今天的这个点已经过了就排到明天——否则会立刻响一次，这显然不是用户要的。
     */
    private fun nextTrigger(hhmm: String): Long {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    private fun pendingIntent(
        context: Context,
        index: Int,
        key: String,
        label: String,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.glpmate.nativeapp.REMINDER.$index"
            putExtra(ReminderReceiver.EXTRA_LABEL, label)
            putExtra(ReminderReceiver.EXTRA_KEY, key)
            putExtra(ReminderReceiver.EXTRA_INDEX, index)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, index, intent, flags)
    }

    /** 缓存里的时间表。解析失败就当作空（宁可没有提醒，也不要崩）。 */
    private fun cachedSchedule(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULE, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Reminder>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Reminder(
                        key = o.optString("key"),
                        label = o.optString("label"),
                        start = o.optString("start", "09:00"),
                        enabled = o.optBoolean("enabled", true),
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "提醒计划解析失败：${e.message}")
            emptyList()
        }
    }

    data class Reminder(val key: String, val label: String, val start: String, val enabled: Boolean)
}
