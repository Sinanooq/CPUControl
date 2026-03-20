package com.cpucontrol

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*

class BatteryFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_battery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvPercent     = view.findViewById<TextView>(R.id.tvBatteryPercent)
        val tvStatus      = view.findViewById<TextView>(R.id.tvBatteryStatus)
        val tvTemp        = view.findViewById<TextView>(R.id.tvBatteryTemp)
        val tvVoltage     = view.findViewById<TextView>(R.id.tvBatteryVoltage)
        val progressBat   = view.findViewById<ProgressBar>(R.id.progressBattery)
        val seekLimit     = view.findViewById<SeekBar>(R.id.seekChargeLimit)
        val tvLimit       = view.findViewById<TextView>(R.id.tvChargeLimit)
        val btnApply      = view.findViewById<MaterialButton>(R.id.btnApplyChargeLimit)
        val tvLimitStatus = view.findViewById<TextView>(R.id.tvChargeLimitStatus)
        val switchNight   = view.findViewById<SwitchMaterial>(R.id.switchNightCharge)

        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

        // Pil bilgilerini yükle
        loadBatteryInfo(tvPercent, tvStatus, tvTemp, tvVoltage, progressBat)

        // Şarj limiti
        val savedLimit = prefs.getInt("charge_limit", 80)
        seekLimit.progress = savedLimit - 50
        tvLimit.text = "$savedLimit%"

        seekLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvLimit.text = "${p + 50}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnApply.setOnClickListener {
            val limit = seekLimit.progress + 50
            btnApply.isEnabled = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { RootHelper.setChargeLimit(limit) }
                btnApply.isEnabled = true
                if (ok) {
                    prefs.edit().putInt("charge_limit", limit).apply()
                    tvLimitStatus.text = "Limit uygulandı: $limit%"
                    tvLimitStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                } else {
                    tvLimitStatus.text = "Uygulama başarısız (cihaz desteklemiyor olabilir)"
                    tvLimitStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
            }
        }

        // Gece şarj planı
        switchNight.isChecked = prefs.getBoolean("night_charge", false)
        switchNight.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("night_charge", checked).apply()
        }

        // Periyodik güncelleme
        scope.launch {
            while (true) {
                delay(10_000)
                loadBatteryInfo(tvPercent, tvStatus, tvTemp, tvVoltage, progressBat)
            }
        }
    }

    private fun loadBatteryInfo(
        tvPercent: TextView, tvStatus: TextView,
        tvTemp: TextView, tvVoltage: TextView, progress: ProgressBar
    ) {
        val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level    = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale    = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status   = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempRaw  = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage  = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val pct = if (scale > 0) (level * 100 / scale) else 0
        val tempC = tempRaw / 10.0f

        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING    -> "Şarj oluyor"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Deşarj oluyor"
            BatteryManager.BATTERY_STATUS_FULL        -> "Dolu"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Şarj olmuyor"
            else -> "Bilinmiyor"
        }

        tvPercent.text = "$pct%"
        tvStatus.text  = "Durum: $statusStr"
        tvTemp.text    = "Sıcaklık: ${"%.1f".format(tempC)}°C"
        tvVoltage.text = "Voltaj: $voltage mV"
        progress.progress = pct

        // Renk: sıcaklığa göre
        val color = when {
            tempC >= 45 -> requireContext().getColor(R.color.accent_orange)
            tempC >= 40 -> requireContext().getColor(R.color.accent_yellow)
            else        -> requireContext().getColor(R.color.accent_green)
        }
        tvPercent.setTextColor(color)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
