package com.cpucontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val p = context.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)

        // Şarj takıldığında session sıfırla
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            val now = System.currentTimeMillis()
            p.edit()
                .putLong("session_start_wall",    now)
                .putLong("session_start_elapsed", SystemClock.elapsedRealtime())
                .putLong("session_start_uptime",  SystemClock.uptimeMillis())
                .apply()
            return
        }

        // Şarjdan çekilince yeni session başlat (özet bildirimi MainActivity'den gönderilir)
        if (intent.action == Intent.ACTION_POWER_DISCONNECTED) {
            val now = System.currentTimeMillis()
            p.edit()
                .putLong("session_start_wall",    now)
                .putLong("session_start_elapsed", SystemClock.elapsedRealtime())
                .putLong("session_start_uptime",  SystemClock.uptimeMillis())
                .apply()
            return
        }

        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Bildirim servisi açıksa yeniden başlat
        val notifEnabled = p.getBoolean("screen_time_notif", false)
        if (notifEnabled) {
            val svc = Intent(context, ScreenTimeNotificationService::class.java)
            context.startForegroundService(svc)
        }

        val littleMin = p.getInt("little_min", 169000)
        val littleMax = p.getInt("little_max", 2200000)
        val bigMin    = p.getInt("big_min",    300000)
        val bigMax    = p.getInt("big_max",    3200000)
        val primeMin  = p.getInt("prime_min",  300000)
        val primeMax  = p.getInt("prime_max",  3350000)
        val gpuMin    = p.getInt("gpu_min",    125000000)
        val gpuMax    = p.getInt("gpu_max",    1400000000)

        CoroutineScope(Dispatchers.IO).launch {
            delay(20000)
            RootHelper.setCpuMinFreq(RootHelper.LITTLE_CORES, littleMin)
            RootHelper.setCpuMaxFreq(RootHelper.LITTLE_CORES, littleMax)
            RootHelper.setCpuMinFreq(RootHelper.BIG_CORES, bigMin)
            RootHelper.setCpuMaxFreq(RootHelper.BIG_CORES, bigMax)
            RootHelper.setCpuMinFreq(listOf(RootHelper.PRIME_CORE), primeMin)
            RootHelper.setCpuMaxFreq(listOf(RootHelper.PRIME_CORE), primeMax)
            RootHelper.setGpuMinFreq(gpuMin)
            RootHelper.setGpuMaxFreq(gpuMax)

            if (p.getBoolean("ttl_fix", false)) {
                val ttl = p.getInt("ttl_value", 64)
                RootHelper.setTtl(ttl)
            }
        }
    }
}
