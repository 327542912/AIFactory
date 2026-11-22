package com.aifactory.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/** 构建/Agent 运行期间保持进程不被系统回收。 */
class KeepAliveService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("aifactory_run", "AIFactory 任务", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "aifactory_run")
            .setContentTitle("AIFactory 正在运行任务")
            .setContentText("构建或 Agent 执行中，请勿清理后台")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aifactory:run").apply {
                acquire(4 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        super.onDestroy()
    }

    companion object {
        private var users = 0

        @Synchronized
        fun start(ctx: Context) {
            users++
            if (users == 1) {
                try {
                    ctx.applicationContext.startForegroundService(
                        Intent(ctx.applicationContext, KeepAliveService::class.java)
                    )
                } catch (_: Exception) {
                }
            }
        }

        @Synchronized
        fun stop(ctx: Context) {
            if (users > 0) users--
            if (users == 0) {
                ctx.applicationContext.stopService(Intent(ctx.applicationContext, KeepAliveService::class.java))
            }
        }
    }
}
