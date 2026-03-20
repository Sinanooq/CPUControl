package com.cpucontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Şarj takılınca charge_start_time'ı güncelle (Android 8+ statik receiver almıyor)
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    val now = System.currentTimeMillis()
                    // Battery Guru mantığı: şarj takılınca session sıfırla
                    getSharedPreferences("cpu_prefs", MODE_PRIVATE).edit()
                        .putLong("session_start_wall",    now)
                        .putLong("session_start_elapsed", android.os.SystemClock.elapsedRealtime())
                        .putLong("session_start_uptime",  android.os.SystemClock.uptimeMillis())
                        // eski key'ler de güncelle (BootReceiver uyumu)
                        .putLong("charge_start_time", now)
                        .putLong("snap_elapsed", android.os.SystemClock.elapsedRealtime())
                        .putLong("snap_uptime",  android.os.SystemClock.uptimeMillis())
                        .apply()
                    sendChargeNotification(charging = true)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // Şarjdan çekilince de session sıfırla (deşarj ölçümü başlasın)
                    val now = System.currentTimeMillis()
                    getSharedPreferences("cpu_prefs", MODE_PRIVATE).edit()
                        .putLong("session_start_wall",    now)
                        .putLong("session_start_elapsed", android.os.SystemClock.elapsedRealtime())
                        .putLong("session_start_uptime",  android.os.SystemClock.uptimeMillis())
                        .apply()
                    sendChargeNotification(charging = false)
                }
            }
        }
    }

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
        val bat = getSystemService(android.os.BatteryManager::class.java)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
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

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val tvRoot    = findViewById<TextView>(R.id.tvRootStatus)

        // Tema toggle — layout'ta varsa bağla, yoksa sessizce geç
        findViewById<android.widget.ImageButton>(R.id.btnThemeToggle)?.let { btn ->
            btn.setImageResource(
                if (isDark) R.drawable.ic_theme_light else R.drawable.ic_theme_dark
            )
            btn.setOnClickListener {
                val newDark = !prefs.getBoolean("dark_theme", true)
                prefs.edit().putBoolean("dark_theme", newDark).apply()
                AppCompatDelegate.setDefaultNightMode(
                    if (newDark) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
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
            override fun onPageSelected(position: Int) {
                bottomNav.selectedItemId = navIds[position]
            }
        })

        bottomNav.setOnItemSelectedListener { item ->
            val idx = navIds.indexOf(item.itemId)
            if (idx >= 0) viewPager.setCurrentItem(idx, false)
            true
        }

        scope.launch {
            val ok = withContext(Dispatchers.IO) { RootHelper.checkRoot() }
            tvRoot.text = if (ok) "● Root izni verildi" else "● Root erişimi yok"
            tvRoot.setTextColor(getColor(
                if (ok) R.color.accent_green else R.color.accent_orange))
        }

        // Şarj receiver'ı dinamik register et (şarj takılı/çekildi)
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
