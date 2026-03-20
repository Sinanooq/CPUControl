package com.cpucontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // İzin verildi, bildirim servisini başlat
            getSharedPreferences("cpu_prefs", MODE_PRIVATE)
                .edit().putBoolean("screen_time_notif", true).apply()
            startForegroundService(Intent(this, ScreenTimeNotificationService::class.java))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    // Session BootReceiver'da kaydediliyor, burada sadece bildirim
                    sendChargeNotification(charging = true)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // Özet bildirimi gönder (BootReceiver session'ı sıfırlamadan önce)
                    sendChargeSummaryNotification()
                }
            }
        }
    }

    /** Şarjdan çekilince: şarj süresindeki ekran açık/kapalı/uyanık/deep sleep özetini bildirir */
    private fun sendChargeSummaryNotification() {
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                ScreenTimeFragment.collectData(applicationContext)
            }

            fun fmt(ms: Long): String {
                if (ms <= 0) return "0dk"
                val h = TimeUnit.MILLISECONDS.toHours(ms)
                val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
                return if (h > 0) "${h}s ${m}dk" else "${m}dk"
            }

            val bat = getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val channelId = "discharge_summary"
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "Şarj Özeti", NotificationManager.IMPORTANCE_DEFAULT)
                nm.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(
                this@MainActivity, 0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE
            )
            val bigText = buildString {
                appendLine("📱 Ekran Açık:   ${fmt(data.screenOnMs)}")
                appendLine("🌙 Ekran Kapalı: ${fmt(data.screenOffMs)}")
                appendLine("👁 Uyanık:       ${fmt(data.awakeMs)}")
                appendLine("💤 Deep Sleep:   ${fmt(data.deepSleepMs)}")
                append("⏱ Toplam:        ${fmt(data.sessionElapsedMs)}")
            }
            val notif = NotificationCompat.Builder(this@MainActivity, channelId)
                .setContentTitle("🔌 Şarjdan Çekildi  •  Pil: $bat%")
                .setContentText("📱 ${fmt(data.screenOnMs)}  🌙 ${fmt(data.screenOffMs)}  💤 ${fmt(data.deepSleepMs)}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(98, notif)
        }
    }

    /** Şarj takılınca basit bildirim */
    private fun sendChargeNotification(charging: Boolean) {
        val channelId = "charge_event"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Şarj Bildirimi", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val bat = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val (title, text, icon) = if (charging)
            Triple("⚡ Şarj Başladı", "Pil: $bat%  •  Ekran süresi sıfırlandı", android.R.drawable.ic_lock_idle_charging)
        else
            Triple("🔌 Şarjdan Çekildi", "Pil: $bat%  •  Deşarj ölçümü başladı", android.R.drawable.ic_lock_idle_low_battery)
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(99, notif)
    }

    private val fragments = listOf(
        { HomeFragment()    as Fragment },
        { CpuGpuFragment()  as Fragment },
        { GameFragment()    as Fragment },
        { ToolsFragment()   as Fragment },
        { MoreFragment()    as Fragment }
    )

    private val navIds = listOf(
        R.id.nav_home, R.id.nav_cpu, R.id.nav_game, R.id.nav_tools, R.id.nav_more
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("cpu_prefs", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_theme", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermission()

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val tvRoot    = findViewById<TextView>(R.id.tvRootStatus)

        findViewById<android.widget.ImageButton>(R.id.btnThemeToggle)?.let { btn ->
            btn.setImageResource(if (isDark) R.drawable.ic_theme_light else R.drawable.ic_theme_dark)
            btn.setOnClickListener {
                val newDark = !prefs.getBoolean("dark_theme", true)
                prefs.edit().putBoolean("dark_theme", newDark).apply()
                AppCompatDelegate.setDefaultNightMode(
                    if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
        }

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(position: Int) = fragments[position]()
        }
        viewPager.offscreenPageLimit = 3
        viewPager.isUserInputEnabled = false

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { bottomNav.selectedItemId = navIds[position] }
        })

        bottomNav.setOnItemSelectedListener { item ->
            val idx = navIds.indexOf(item.itemId)
            if (idx >= 0) viewPager.setCurrentItem(idx, false)
            true
        }

        scope.launch {
            val ok = withContext(Dispatchers.IO) { RootHelper.checkRoot() }
            tvRoot.text = if (ok) "● Root izni verildi" else "● Root erişimi yok"
            tvRoot.setTextColor(getColor(if (ok) R.color.accent_green else R.color.accent_orange))
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching { unregisterReceiver(powerReceiver) }
    }
}
