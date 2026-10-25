// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.pow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the miner alive while the app is
 * backgrounded. Holds a partial wake lock so the CPU stays awake
 * between template polls.
 *
 * CRITICAL: Android 14 (API 34) requires startForeground() to be called
 * with the foregroundServiceType parameter when the manifest declares
 * android:foregroundServiceType="dataSync". Without it, the system
 * throws ForegroundServiceTypeNotAllowed and the app crashes.
 */
class MinerService : Service() {

    private val handler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "miner engine crashed", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    private var wakeLock: PowerManager.WakeLock? = null
    private var engineJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noidmobile:miner").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: pass the foregroundServiceType on Android 14+ (API 34+).
        // Without this, the system crashes the app with:
        //   ForegroundServiceTypeNotAllowed: Starting FGS with type dataSync
        //   targetSDK=34 is not allowed.
        val notif = buildNotification("Mining…", "0 H/s")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (engineJob?.isActive != true) {
            engineJob = scope.launch {
                try {
                    MinerEngine.runLoop(applicationContext) { hashrate, status ->
                        try {
                            val notif2 = buildNotification(status, "%.1f H/s".format(hashrate))
                            val nm = getSystemService(NotificationManager::class.java)
                            nm.notify(NOTIF_ID, notif2)
                        } catch (t: Throwable) {
                            Log.w(TAG, "notif update failed: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "MinerEngine.runLoop threw", t)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engineJob?.cancel()
        engineJob = null
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "NOID Miner",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Background Poseidon2b mining"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(contentTitle: String, contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "noidmobile_miner"
        const val NOTIF_ID = 0x4E01D
        private const val TAG = "MinerService"
    }
}
