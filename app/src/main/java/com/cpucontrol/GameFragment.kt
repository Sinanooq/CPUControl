package com.cpucontrol

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*
class GameFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Bilinen oyunlar: paket adı → görünen ad
    private val knownGames = mutableMapOf(
        "com.tencent.ig"                    to "PUBG Mobile",
        "com.pubg.imobile"                  to "PUBG Mobile (Global)",
        "com.activision.callofduty.shooter" to "Call of Duty Mobile",
        "com.riotgames.league.wildrift"     to "Wild Rift",
        "com.miHoYo.GenshinImpact"          to "Genshin Impact",
        "com.miHoYo.Honkai3rd"              to "Honkai Impact 3",
        "com.HoYoverse.Honkai3rdGlobal"     to "Honkai Impact 3 (Global)",
        "com.miHoYo.hkrpg"                  to "Honkai: Star Rail",
        "com.supercell.clashofclans"        to "Clash of Clans",
        "com.supercell.clashroyale"         to "Clash Royale",
        "com.garena.free.fire"              to "Free Fire",
        "com.dts.freefireth"                to "Free Fire (TH)",
        "com.mobile.legends"                to "Mobile Legends",
        "com.vng.mlbbvn"                    to "Mobile Legends (VN)",
        "com.epicgames.fortnite"            to "Fortnite",
        "com.ea.game.nfs14_row"             to "Need for Speed",
        "com.ea.games.r3_row"               to "Real Racing 3",
        "com.gameloft.android.ANMP.GloftA9HM" to "Asphalt 9",
        "com.mojang.minecraftpe"            to "Minecraft",
        "com.netease.lztgglobal"            to "Rules of Survival",
        "com.proximabeta.mf"                to "Metal Slug: Awakening"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_game, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val switchGameDetect = view.findViewById<SwitchMaterial>(R.id.switchGameDetect)
        val switchOverlay    = view.findViewById<SwitchMaterial>(R.id.switchOverlay)
        val switchTcpOpt     = view.findViewById<SwitchMaterial>(R.id.switchTcpOpt)
        val llGameList       = view.findViewById<LinearLayout>(R.id.llGameList)
        val btnAddGame       = view.findViewById<MaterialButton>(R.id.btnAddGame)
        val tvDetectStatus   = view.findViewById<TextView>(R.id.tvDetectStatus)

        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

        // Kullanıcının eklediği oyunları yükle
        val userGames = prefs.getStringSet("user_games", emptySet()) ?: emptySet()
        userGames.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) knownGames[parts[0]] = parts[1]
        }

        switchGameDetect.isChecked = prefs.getBoolean("game_detect_enabled", false)
        switchOverlay.isChecked    = prefs.getBoolean("game_overlay_enabled", false)

        switchGameDetect.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("game_detect_enabled", checked).apply()
            val ctx = requireContext()
            val intent = android.content.Intent(ctx, GameDetectorService::class.java)
            if (checked) {
                ctx.startForegroundService(intent)
                tvDetectStatus.text = "Oyun algılama aktif"
                tvDetectStatus.setTextColor(ctx.getColor(R.color.accent_green))
            } else {
                ctx.stopService(intent)
                tvDetectStatus.text = "Oyun algılama kapalı"
                tvDetectStatus.setTextColor(ctx.getColor(R.color.text_secondary))
            }
        }

        switchOverlay.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("game_overlay_enabled", checked).apply()
            val ctx = requireContext()
            val intent = android.content.Intent(ctx, OverlayService::class.java)
            if (checked) {
                if (android.provider.Settings.canDrawOverlays(ctx)) {
                    ctx.startForegroundService(intent)
                } else {
                    switchOverlay.isChecked = false
                    val settingsIntent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    )
                    startActivity(settingsIntent)
                }
            } else {
                ctx.stopService(intent)
            }
        }

        switchTcpOpt.isChecked = prefs.getBoolean("game_tcp_opt", true)
        switchTcpOpt.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("game_tcp_opt", checked).apply()
        }

        // Oyun listesini doldur
        buildGameList(llGameList, prefs)

        btnAddGame.setOnClickListener {
            showAddGameDialog(llGameList, prefs)
        }

        tvDetectStatus.text = if (switchGameDetect.isChecked) "Oyun algılama aktif" else "Oyun algılama kapalı"
        tvDetectStatus.setTextColor(requireContext().getColor(
            if (switchGameDetect.isChecked) R.color.accent_green else R.color.text_secondary))
    }

    private fun buildGameList(container: LinearLayout, prefs: android.content.SharedPreferences) {
        container.removeAllViews()
        knownGames.forEach { (pkg, name) ->
            val row = layoutInflater.inflate(R.layout.item_game_row, container, false)
            row.findViewById<TextView>(R.id.tvGameName).text = name
            row.findViewById<TextView>(R.id.tvGamePkg).text = pkg
            val btnRemove = row.findViewById<MaterialButton>(R.id.btnRemoveGame)
            // Varsayılan oyunlar silinemez
            val isUserAdded = prefs.getStringSet("user_games", emptySet())
                ?.any { it.startsWith(pkg) } == true
            btnRemove.visibility = if (isUserAdded) View.VISIBLE else View.GONE
            btnRemove.setOnClickListener {
                knownGames.remove(pkg)
                val updated = (prefs.getStringSet("user_games", emptySet()) ?: emptySet())
                    .filter { !it.startsWith(pkg) }.toSet()
                prefs.edit().putStringSet("user_games", updated).apply()
                buildGameList(container, prefs)
            }
            container.addView(row)
        }
    }

    private fun showAddGameDialog(container: LinearLayout, prefs: android.content.SharedPreferences) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_game, null)
        val etName = dialogView.findViewById<EditText>(R.id.etGameName)
        val etPkg  = dialogView.findViewById<EditText>(R.id.etGamePkg)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Oyun Ekle")
            .setView(dialogView)
            .setPositiveButton("Ekle") { _, _ ->
                val name = etName.text.toString().trim()
                val pkg  = etPkg.text.toString().trim()
                if (name.isNotEmpty() && pkg.isNotEmpty()) {
                    knownGames[pkg] = name
                    val current = prefs.getStringSet("user_games", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    current.add("$pkg|$name")
                    prefs.edit().putStringSet("user_games", current).apply()
                    buildGameList(container, prefs)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
