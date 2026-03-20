package com.cpucontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*

class NetworkFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val refreshRates = listOf(60, 90, 120)

    private lateinit var switchTethering: SwitchMaterial
    private lateinit var tvTetheringInfo: TextView
    private lateinit var tvTetheringStatus: TextView

    private val shareReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getStringExtra(WifiShareService.EXTRA_STATUS) ?: return
            val msg    = intent.getStringExtra(WifiShareService.EXTRA_MSG) ?: ""
            when (status) {
                "running" -> {
                    switchTethering.isChecked = true
                    tvTetheringInfo.text = "SSID: ${WifiShareService.groupSsid}  •  Şifre: ${WifiShareService.groupPass}"
                    tvTetheringStatus.text = "Aktif — bağlanan cihazlar otomatik internet alır"
                    tvTetheringStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                }
                "error" -> {
                    switchTethering.isChecked = false
                    tvTetheringInfo.text = ""
                    tvTetheringStatus.text = "Hata: $msg"
                    tvTetheringStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
                "stopped" -> {
                    switchTethering.isChecked = false
                    tvTetheringInfo.text = ""
                    tvTetheringStatus.text = "Durduruldu"
                    tvTetheringStatus.setTextColor(requireContext().getColor(R.color.text_secondary))
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_network, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        switchTethering   = view.findViewById(R.id.switchTethering)
        tvTetheringInfo   = view.findViewById(R.id.tvTetheringInfo)
        tvTetheringStatus = view.findViewById(R.id.tvTetheringStatus)
        val etSsid = view.findViewById<EditText>(R.id.etShareSsid)
        val etPass = view.findViewById<EditText>(R.id.etSharePass)
        val prefs  = requireContext().getSharedPreferences("cpu_prefs", 0)

        etSsid.setText(prefs.getString("share_ssid", "CPUControl"))
        etPass.setText(prefs.getString("share_pass", "cpucontrol123"))

        if (WifiShareService.isRunning) {
            switchTethering.isChecked = true
            tvTetheringInfo.text = "SSID: ${WifiShareService.groupSsid}  •  Şifre: ${WifiShareService.groupPass}"
            tvTetheringStatus.text = "Aktif — bağlanan cihazlar otomatik internet alır"
            tvTetheringStatus.setTextColor(requireContext().getColor(R.color.accent_green))
        }

        switchTethering.setOnCheckedChangeListener { _, checked ->
            val ctx = requireContext()
            if (checked) {
                val ssid = etSsid.text.toString().trim().ifEmpty { "CPUControl" }
                val pass = etPass.text.toString().trim().ifEmpty { "cpucontrol123" }
                prefs.edit().putString("share_ssid", ssid).putString("share_pass", pass).apply()
                ctx.startForegroundService(
                    Intent(ctx, WifiShareService::class.java)
                        .setAction(WifiShareService.ACTION_START)
                        .putExtra(WifiShareService.EXTRA_SSID, ssid)
                        .putExtra(WifiShareService.EXTRA_PASS, pass)
                )
                tvTetheringStatus.text = "Başlatılıyor..."
                tvTetheringStatus.setTextColor(requireContext().getColor(R.color.text_secondary))
            } else {
                ctx.startService(Intent(ctx, WifiShareService::class.java).setAction(WifiShareService.ACTION_STOP))
            }
        }

        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(shareReceiver, IntentFilter(WifiShareService.BROADCAST_STATUS))

        val switchTcp        = view.findViewById<SwitchMaterial>(R.id.switchTcp)
        val switchLowLatency = view.findViewById<SwitchMaterial>(R.id.switchLowLatency)
        val switchWifi       = view.findViewById<SwitchMaterial>(R.id.switchWifiPowerSave)
        val tvCongestion     = view.findViewById<TextView>(R.id.tvTcpCongestion)
        val seekRefresh      = view.findViewById<SeekBar>(R.id.seekRefreshRate)
        val tvRefresh        = view.findViewById<TextView>(R.id.tvRefreshRate)
        val btnRefresh       = view.findViewById<MaterialButton>(R.id.btnApplyRefresh)
        val tvRefreshStatus  = view.findViewById<TextView>(R.id.tvRefreshStatus)

        // DNS view'ları
        val tvCurrentDns     = view.findViewById<TextView>(R.id.tvCurrentDns)
        val btnDnsCloudflare = view.findViewById<MaterialButton>(R.id.btnDnsCloudflare)
        val btnDnsGoogle     = view.findViewById<MaterialButton>(R.id.btnDnsGoogle)
        val btnDnsAdguard    = view.findViewById<MaterialButton>(R.id.btnDnsAdguard)
        val btnDnsCustom     = view.findViewById<MaterialButton>(R.id.btnDnsCustom)
        val etCustomDns      = view.findViewById<EditText>(R.id.etCustomDns)
        val tvDnsStatus      = view.findViewById<TextView>(R.id.tvDnsStatus)

        // TCP durumunu oku
        scope.launch {
            val cong = withContext(Dispatchers.IO) { RootHelper.getTcpCongestion() }
            tvCongestion.text = "Mevcut: $cong"
            switchTcp.isChecked = cong == "bbr"
        }

        switchTcp.isChecked = prefs.getBoolean("tcp_opt", false)
        switchTcp.setOnCheckedChangeListener { _, checked ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    if (checked) RootHelper.applyTcpOptimization()
                    else RootHelper.resetTcpOptimization()
                }
                prefs.edit().putBoolean("tcp_opt", checked && ok).apply()
                val cong = withContext(Dispatchers.IO) { RootHelper.getTcpCongestion() }
                tvCongestion.text = "Mevcut: $cong"
                if (!ok) switchTcp.isChecked = false
            }
        }

        switchLowLatency.isChecked = prefs.getBoolean("low_latency", false)
        switchLowLatency.setOnCheckedChangeListener { _, checked ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    RootHelper.runAsRoot(
                        if (checked) "echo 1 > /proc/sys/net/ipv4/tcp_low_latency"
                        else "echo 0 > /proc/sys/net/ipv4/tcp_low_latency"
                    )
                }
                prefs.edit().putBoolean("low_latency", checked).apply()
            }
        }

        switchWifi.isChecked = prefs.getBoolean("wifi_power_save_off", false)
        switchWifi.setOnCheckedChangeListener { _, checked ->
            scope.launch {
                withContext(Dispatchers.IO) { RootHelper.setWifiPowerSave(!checked) }
                prefs.edit().putBoolean("wifi_power_save_off", checked).apply()
            }
        }

        // Yenileme hızı
        val savedRate = prefs.getInt("refresh_rate", 90)
        val savedIdx  = refreshRates.indexOf(savedRate).coerceAtLeast(0)
        seekRefresh.progress = savedIdx
        tvRefresh.text = "${refreshRates[savedIdx]} Hz"

        seekRefresh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvRefresh.text = "${refreshRates[p]} Hz"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnRefresh.setOnClickListener {
            val rate = refreshRates[seekRefresh.progress]
            btnRefresh.isEnabled = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { RootHelper.setRefreshRate(rate) }
                btnRefresh.isEnabled = true
                if (ok) {
                    prefs.edit().putInt("refresh_rate", rate).apply()
                    tvRefreshStatus.text = "Uygulandı: $rate Hz"
                    tvRefreshStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                } else {
                    tvRefreshStatus.text = "Uygulama başarısız"
                    tvRefreshStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
            }
        }

        // DNS — mevcut DNS'i oku
        loadCurrentDns(tvCurrentDns)

        fun applyDns(dns1: String, dns2: String) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { RootHelper.setDns(dns1, dns2) }
                if (ok) {
                    tvDnsStatus.text = "DNS uygulandı: $dns1"
                    tvDnsStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                    loadCurrentDns(tvCurrentDns)
                } else {
                    tvDnsStatus.text = "DNS uygulanamadı"
                    tvDnsStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
            }
        }

        btnDnsCloudflare.setOnClickListener { applyDns("1.1.1.1", "1.0.0.1") }
        btnDnsGoogle.setOnClickListener     { applyDns("8.8.8.8", "8.8.4.4") }
        btnDnsAdguard.setOnClickListener    { applyDns("94.140.14.14", "94.140.15.15") }

        btnDnsCustom.setOnClickListener {
            val custom = etCustomDns.text.toString().trim()
            if (custom.isNotEmpty()) {
                applyDns(custom, custom)
            } else {
                tvDnsStatus.text = "Geçerli bir DNS adresi girin"
                tvDnsStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
            }
        }
    }

    private fun loadCurrentDns(tvCurrentDns: TextView) {
        scope.launch {
            val dns = withContext(Dispatchers.IO) {
                RootHelper.runAsRoot("getprop net.dns1").second.trim().ifEmpty { "—" }
            }
            tvCurrentDns.text = "Mevcut DNS: $dns"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(shareReceiver)
        scope.cancel()
    }
}
