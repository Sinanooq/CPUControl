package com.cpucontrol

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*

class ProfileFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvActive        = view.findViewById<TextView>(R.id.tvActiveProfile)
        val tvProfileStatus = view.findViewById<TextView>(R.id.tvProfileStatus)
        val btnPil          = view.findViewById<MaterialButton>(R.id.btnPilTasarrufu)
        val btnDengeli      = view.findViewById<MaterialButton>(R.id.btnDengeli)
        val btnPerformans   = view.findViewById<MaterialButton>(R.id.btnPerformans)
        val btnOyun         = view.findViewById<MaterialButton>(R.id.btnOyun)
        val btnGece         = view.findViewById<MaterialButton>(R.id.btnGece)
        val switchAuto      = view.findViewById<SwitchMaterial>(R.id.switchAuto)
        val switchCharging  = view.findViewById<SwitchMaterial>(R.id.switchChargingPerf)
        val seekLow         = view.findViewById<SeekBar>(R.id.seekLowBattery)
        val tvLow           = view.findViewById<TextView>(R.id.tvLowBattery)
        val seekMid         = view.findViewById<SeekBar>(R.id.seekMidBattery)
        val tvMid           = view.findViewById<TextView>(R.id.tvMidBattery)
        val tvAutoStatus    = view.findViewById<TextView>(R.id.tvAutoStatus)

        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

        // Mevcut aktif profili göster
        val active = prefs.getString("active_profile", "") ?: ""
        tvActive.text = Profiles.isimler[active] ?: "—"

        // Ayarları yükle
        switchAuto.isChecked     = prefs.getBoolean("auto_profile_enabled", false)
        switchCharging.isChecked = prefs.getBoolean("auto_charging_perf", true)
        seekLow.progress         = prefs.getInt("auto_low_battery", 20)
        seekMid.progress         = prefs.getInt("auto_mid_battery", 50)
        tvLow.text = "%${seekLow.progress}"
        tvMid.text = "%${seekMid.progress}"

        // Manuel profil butonları
        fun applyProfile(key: String, btn: MaterialButton) {
            btn.isEnabled = false
            scope.launch {
                withContext(Dispatchers.IO) { Profiles.apply(Profiles.hepsi[key]!!) }
                prefs.edit().putString("active_profile", key).apply()
                tvActive.text = Profiles.isimler[key] ?: "—"
                btn.isEnabled = true
            }
        }

        btnPil.setOnClickListener        { applyProfile("pil_tasarrufu", btnPil) }
        btnDengeli.setOnClickListener    { applyProfile("dengeli", btnDengeli) }
        btnPerformans.setOnClickListener { applyProfile("performans", btnPerformans) }
        btnOyun.setOnClickListener       { applyProfile("oyun", btnOyun) }
        btnGece.setOnClickListener       { applyProfile("gece", btnGece) }

        // Otomatik profil toggle
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_profile_enabled", checked).apply()
            val ctx = requireContext()
            val intent = Intent(ctx, AutoProfileService::class.java)
            if (checked) {
                ctx.startForegroundService(intent)
                tvAutoStatus.text = "Otomatik profil aktif — 30 saniyede bir kontrol edilir"
            } else {
                ctx.stopService(intent)
                tvAutoStatus.text = "Otomatik profil kapalı"
            }
        }

        switchCharging.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_charging_perf", checked).apply()
        }

        seekLow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvLow.text = "%$p"
                if (p >= seekMid.progress) seekMid.progress = p + 1
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt("auto_low_battery", sb.progress).apply()
            }
        })

        seekMid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvMid.text = "%$p"
                if (p <= seekLow.progress) seekLow.progress = p - 1
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt("auto_mid_battery", sb.progress).apply()
            }
        })

        // Servis zaten çalışıyorsa status göster
        if (switchAuto.isChecked) {
            tvAutoStatus.text = "Otomatik profil aktif — 30 saniyede bir kontrol edilir"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
