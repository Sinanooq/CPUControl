package com.cpucontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED  -> onChargeConnected(context)
            Intent.ACTION_POWER_DISCONNECTED -> onChargeDisconnected(context)
            Intent.ACTION_BOOT_COMPLETED   -> onBoot(context)
        }
    }

    // ── Şarj takıldı ────────────────────────────────────────────────────────
    private fun onChargeConnected(ctx: Context) {
        // Yeni session başlat
        saveSession(ctx)

        // Bildirim: şarj başladı
        val bat = ctx.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        sendSimpleNotif(
            ctx,
            id       = 99,
            channel  = "charge_event",
            chName   = "Şarj Bildirimi",
            title    = "⚡ Şarj Başladı",
            text     = "Pil: $bat%  •  Ekran süresi ölçümü başladı",
            icon     = android.R.drawable.ic_lock_idle_charging,
            autoCancel = true
        )

        // Ongoing bildirim servisini başlat (izin varsa)
        val p = ctx.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)
        if (p.getBoolean("screen_time_notif", true)) {
            ctx.startForegroundService(Intent(ctx, ScreenTimeNotificationService::class.java))
        }
    }

    // ── Şarjdan çekildi ─────────────────────────────────────────────────────
    private fun onChargeDisconnected(ctx: Context) {
        // Önce mevcut session verisiyle özet bildirimi gönder
        CoroutineScope(Dispatchers.IO).launch {
            val data = ScreenTimeFragment.collectData(ctx)
            sendSummaryNotif(ctx, data)
            // Sonra yeni session başlat (deşarj dönemi)
            saveSession(ctx)
        }
    }

    // ── Cihaz açıldı ────────────────────────────────────────────────────────
    private fun onBoot(ctx: Context) {
        val p = ctx.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)

        // Session yoksa başlat
        if (p.getLong("session_start_wall", 0L) == 0L) saveSession(ctx)

        // Bildirim servisi
        if (p.getBoolean("screen_time_notif", true)) {
            ctx.startForegroundService(Intent(ctx, ScreenTimeNotificationService::class.java))
        }

        // CPU/GPU ayarları
        val littleMin = p.getInt("little_min", 169000)
        val littleMax = p.getInt("little_max", 2200000)
        val bigMin    = p.getInt("big_min",    300000)
        val bigMax    = p.getInt("big_max",    3200000)
        val primeMin  = p.getInt("prime_min",  300000)
        val primeMax  = p.getInt("prime_max",  3350000)
        val gpuMin    = p.getInt("gpu_min",    125000000)
        val gpuMax    = p.getInt("gpu_max",    1400000000)

        CoroutineScope(Dispatchers.IO).launch {
            delay(20_000)
            RootHelper.setCpuMinFreq(RootHelper.LITTLE_CORES, littleMin)
            RootHelper.setCpuMaxFreq(RootHelper.LITTLE_CORES, littleMax)
            RootHelper.setCpuMinFreq(RootHelper.BIG_CORES, bigMin)
            RootHelper.setCpuMaxFreq(RootHelper.BIG_CORES, bigMax)
            RootHelper.setCpuMinFreq(listOf(RootHelper.PRIME_CORE), primeMin)
            RootHelper.setCpuMaxFreq(listOf(RootHelper.PRIME_CORE), primeMax)
            RootHelper.setGpuMinFreq(gpuMin)
            RootHelper.setGpuMaxFreq(gpuMax)
            if (p.getBoolean("ttl_fix", false)) {
                RootHelper.setTtl(p.getInt("ttl_value", 64))
            }
        }
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────

    private fun saveSession(ctx: Context) {
        ctx.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE).edit()
            .putLong("session_start_wall",    System.currentTimeMillis())
            .putLong("session_start_elapsed", SystemClock.elapsedRealtime())
            .putLong("session_start_uptime",  SystemClock.uptimeMillis())
            .apply()
    }

    private fun sendSummaryNotif(ctx: Context, data: ScreenTimeFragment.ScreenData) {
        val bat = ctx.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channelId = "discharge_summary"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Şarj Özeti", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pi = PendingIntent.getActivity(
            ctx, 0,
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        val bigText = if (data.sessionElapsedMs > 0L) buildString {
            appendLine("📱 Ekran Açık:   ${fmt(data.screenOnMs)}")
            appendLine("🌙 Ekran Kapalı: ${fmt(data.screenOffMs)}")
            appendLine("👁 Uyanık:       ${fmt(data.awakeMs)}")
            appendLine("💤 Deep Sleep:   ${fmt(data.deepSleepMs)}")
            append("⏱ Toplam:        ${fmt(data.sessionElapsedMs)}")
        } else "Ölçüm verisi yok"

        val shortText = if (data.sessionElapsedMs > 0L)
            "📱 ${fmt(data.screenOnMs)}  🌙 ${fmt(data.screenOffMs)}  💤 ${fmt(data.deepSleepMs)}"
        else "Ölçüm verisi yok"

        val notif = NotificationCompat.Builder(ctx, channelId)
            .setContentTitle("🔌 Şarjdan Çekildi  •  Pil: $bat%")
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(98, notif)
    }

    private fun sendSimpleNotif(
        ctx: Context, id: Int, channel: String, chName: String,
        title: String, text: String, icon: Int, autoCancel: Boolean
    ) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channel, chName, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pi = PendingIntent.getActivity(
            ctx, id,
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pi)
            .setAutoCancel(autoCancel)
            .build()
        nm.notify(id, notif)
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0dk"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}s ${m}dk" else "${m}dk"
    }
}
