package com.cpucontrol

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Bildirim izni — verilince servisi başlat
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            getSharedPreferences("cpu_prefs", MODE_PRIVATE)
                .edit().putBoolean("screen_time_notif", true).apply()
            startForegroundService(Intent(this, ScreenTimeNotificationService::class.java))
        }
    }

    private fun requestNotifPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // İzin zaten var, servisi başlat
                getSharedPreferences("cpu_prefs", MODE_PRIVATE)
                    .edit().putBoolean("screen_time_notif", true).apply()
                startForegroundService(Intent(this, ScreenTimeNotificationService::class.java))
            }
        } else {
            // Android 12 ve altı — izin gerekmez
            getSharedPreferences("cpu_prefs", MODE_PRIVATE)
                .edit().putBoolean("screen_time_notif", true).apply()
            startForegroundService(Intent(this, ScreenTimeNotificationService::class.java))
        }
    }

    private val fragments = listOf(
        { HomeFragment()   as Fragment },
        { CpuGpuFragment() as Fragment },
        { GameFragment()   as Fragment },
        { ToolsFragment()  as Fragment },
        { MoreFragment()   as Fragment }
    )

    private val navIds = listOf(
        R.id.nav_home, R.id.nav_cpu, R.id.nav_game, R.id.nav_tools, R.id.nav_more
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("cpu_prefs", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_theme", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bildirim izni iste + servisi başlat
        requestNotifPermIfNeeded()

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val tvRoot    = findViewById<TextView>(R.id.tvRootStatus)

        // Tema butonu
        findViewById<android.widget.ImageButton>(R.id.btnThemeToggle)?.let { btn ->
            btn.setImageResource(if (isDark) R.drawable.ic_theme_light else R.drawable.ic_theme_dark)
            btn.setOnClickListener {
                val newDark = !prefs.getBoolean("dark_theme", true)
                prefs.edit().putBoolean("dark_theme", newDark).apply()
                AppCompatDelegate.setDefaultNightMode(
                    if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                finish()
            }
        }

        // ViewPager
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

        // Root durumu
        scope.launch {
            val ok = withContext(Dispatchers.IO) { RootHelper.checkRoot() }
            tvRoot.text = if (ok) "● Root izni verildi" else "● Root erişimi yok"
            tvRoot.setTextColor(getColor(if (ok) R.color.accent_green else R.color.accent_orange))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
