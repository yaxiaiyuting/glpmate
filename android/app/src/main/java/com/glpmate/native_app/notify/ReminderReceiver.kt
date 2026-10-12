package com.glpmate.native_app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.glpmate.native_app.MainActivity
import com.glpmate.native_app.R

/**
 * 提醒到点：发一条本地通知，然后把明天的同一个时间再排上。
 *
 * 为什么要在响过之后再排一次：AlarmManager 的 setExact 是**一次性**的，
 * 不重排就只响一次。这里在 onReceive 里续排，形成每日循环。
 *
 * 通知内容只写"该记录 X 了"，**不写任何医疗建议或剂量建议**——
 * 提醒是记录助手，不是用药指导。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "记录"
        val key = intent.getStringExtra(EXTRA_KEY) ?: ""
        val index = intent.getIntExtra(EXTRA_INDEX, 0)

        // 先续排明天的，避免后面 notify 抛异常导致提醒断掉
        ReminderScheduler.reschedule(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            Log.w(TAG, "拿不到 NotificationManager")
            return
        }
        ensureChannel(manager)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 通知权限没给：什么都做不了，但要留下痕迹方便排查
            Log.w(TAG, "没有通知权限，$label 的提醒无法显示")
            return
        }

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val contentIntent = PendingIntent.getActivity(context, index, open, flags)

        val text = when (key) {
            "water" -> "补点水吧，顺便记一下喝了多少"
            "weigh_in" -> "称个体重，记录下来"
            "breakfast", "lunch", "dinner" -> "记录这一餐吃了什么"
            else -> "打开应用记录一下"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(label)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(index + 1, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "发通知被拒：${e.message}")
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "记录提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "到点提醒你记录饮水、餐食、体重等内容"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "GlpReminder"
        const val CHANNEL_ID = "glpmate_reminders"
        const val EXTRA_LABEL = "label"
        const val EXTRA_KEY = "key"
        const val EXTRA_INDEX = "index"
    }
}
