package com.cpucontrol

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class GameDetectorService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val CHANNEL_ID = "game_detector"
    private var lastPkg = ""
    private var previousProfile = "dengeli"

    private val knownGames = setOf(
        "com.tencent.ig", "com.pubg.imobile",
        "com.activision.callofduty.shooter",
        "com.riotgames.league.wildrift",
        "com.miHoYo.GenshinImpact", "com.miHoYo.Honkai3rd",
        "com.HoYoverse.Honkai3rdGlobal", "com.miHoYo.hkrpg",
        "com.supercell.clashofclans", "com.supercell.clashroyale",
        "com.garena.free.fire", "com.dts.freefireth",
        "com.mobile.legends", "com.vng.mlbbvn",
        "com.epicgames.fortnite", "com.ea.game.nfs14_row",
        "com.ea.games.r3_row", "com.gameloft.android.ANMP.GloftA9HM",
        "com.mojang.minecraftpe", "com.netease.lztgglobal",
        "com.proximabeta.mf"
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(2, buildNotif("Oyun algılama aktif"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { detectLoop() }
        return START_STICKY
    }

    private suspend fun detectLoop() {
        while (true) {
            val prefs = getSharedPreferences("cpu_prefs", MODE_PRIVATE)
            val enabled = prefs.getBoolean("game_detect_enabled", false)

            if (enabled) {
                // Kullanıcının eklediği oyunları da dahil et
                val userGames = (prefs.getStringSet("user_games", emptySet()) ?: emptySet())
                    .map { it.split("|").firstOrNull() ?: "" }.toSet()
                val allGames = knownGames + userGames

                val pkg = RootHelper.getForegroundPackage()

                if (pkg != lastPkg) {
                    // Önce uygulama bazlı profil kontrolü
                    val appSpecificProfile = if (pkg.isNotEmpty())
                        prefs.getString("app_profile_$pkg", null) else null

                    if (appSpecificProfile != null) {
                        // Uygulama bazlı profil atanmış
                        previousProfile = prefs.getString("active_profile", "dengeli") ?: "dengeli"
                        val profile = Profiles.hepsi[appSpecificProfile] ?: Profiles.Dengeli
                        Profiles.apply(profile)
                        prefs.edit().putString("active_profile", appSpecificProfile).apply()
                        updateNotif("Uygulama profili: ${Profiles.isimler[appSpecificProfile] ?: appSpecificProfile}")
                    } else if (pkg in allGames) {
                        // Bilinen oyun — oyun profiline geç
                        previousProfile = prefs.getString("active_profile", "dengeli") ?: "dengeli"
                        Profiles.apply(Profiles.hepsi["oyun"]!!)
                        prefs.edit().putString("active_profile", "oyun").apply()
                        if (prefs.getBoolean("game_tcp_opt", true)) {
                            RootHelper.applyTcpOptimization()
                        }
                        updateNotif("Oyun modu: aktif")
                    } else {
                        // Önceki uygulama oyun/özel profiliyse geri dön
                        val prevHadProfile = prefs.getString("app_profile_$lastPkg", null) != null
                        val prevWasGame = lastPkg in allGames
                        if (prevHadProfile || prevWasGame) {
                            val profile = Profiles.hepsi[previousProfile] ?: Profiles.Dengeli
                            Profiles.apply(profile)
                            prefs.edit().putString("active_profile", previousProfile).apply()
                            if (prevWasGame && prefs.getBoolean("game_tcp_opt", true)) {
                                RootHelper.resetTcpOptimization()
                            }
                            updateNotif("Oyun algılama aktif")
                        }
                    }
                    lastPkg = pkg
                }
            }
            delay(3_000)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Oyun Algılama", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
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

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(2, buildNotif(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
