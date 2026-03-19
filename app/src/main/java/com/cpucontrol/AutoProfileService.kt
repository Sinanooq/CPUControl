package com.cpucontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AutoProfileService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val CHANNEL_ID = "cpu_control_service"
    private var lastApplied = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Otomatik profil aktif"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { monitorLoop() }
        return START_STICKY
    }

    private suspend fun monitorLoop() {
        while (true) {
            val prefs = getSharedPreferences("cpu_prefs", MODE_PRIVATE)
            val autoEnabled = prefs.getBoolean("auto_profile_enabled", false)

            if (autoEnabled) {
                val profile = determineProfile(prefs)
                if (profile != lastApplied) {
                    Profiles.apply(Profiles.hepsi[profile]!!)
                    lastApplied = profile
                    prefs.edit().putString("active_profile", profile).apply()
                    updateNotification("Profil: ${Profiles.isimler[profile]}")
                }
            }
            delay(30_000)
        }
    }

    private fun determineProfile(prefs: android.content.SharedPreferences): String {
        val batteryLevel = getBatteryLevel()
        val charging     = isCharging()
        val thermalLevel = getThermalLevel()

        if (thermalLevel >= 3) return "pil_tasarrufu"
        if (charging && prefs.getBoolean("auto_charging_perf", true)) return "performans"

        val lowBattery = prefs.getInt("auto_low_battery", 20)
        val midBattery = prefs.getInt("auto_mid_battery", 50)

        return when {
            batteryLevel <= lowBattery -> "pil_tasarrufu"
            batteryLevel <= midBattery -> "dengeli"
            else -> prefs.getString("auto_default_profile", "dengeli") ?: "dengeli"
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getThermalLevel(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                pm.currentThermalStatus
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CPU Kontrol Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Otomatik profil yönetimi" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CPU Kontrol")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
