package com.glpmate.native_app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用升级后重新排提醒。
 *
 * 为什么必须单独做：AlarmManager 里的闹钟**不跨重启**，手机重启后全部丢失。
 * 这个接收器只读 SharedPreferences 里缓存的时间表——
 * **不去启动 Python 后端**（在广播接收器里起解释器既慢又容易被系统杀掉），
 * 所以后端没跑的时候提醒照样准点响。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        Log.i(ReminderReceiver.TAG, "收到 $action，重排提醒")
        ReminderScheduler.reschedule(context)
    }
}
