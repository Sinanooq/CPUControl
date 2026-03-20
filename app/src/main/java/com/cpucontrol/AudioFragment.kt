package com.cpucontrol

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class AudioFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var enhancer: LoudnessEnhancer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_audio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val am    = requireContext().getSystemService(AudioManager::class.java)
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

        val seekMedia = view.findViewById<SeekBar>(R.id.seekMedia)
        val seekRing  = view.findViewById<SeekBar>(R.id.seekRing)
        val seekAlarm = view.findViewById<SeekBar>(R.id.seekAlarm)
        val seekBoost = view.findViewById<SeekBar>(R.id.seekGain)
        val tvMedia   = view.findViewById<TextView>(R.id.tvMediaVal)
        val tvRing    = view.findViewById<TextView>(R.id.tvRingVal)
        val tvAlarm   = view.findViewById<TextView>(R.id.tvAlarmVal)
        val tvBoost   = view.findViewById<TextView>(R.id.tvGainVal)
        val tvStatus  = view.findViewById<TextView>(R.id.tvGainStatus)
        val btnApply  = view.findViewById<MaterialButton>(R.id.btnGainApply)
        val btnReset  = view.findViewById<MaterialButton>(R.id.btnGainReset)
        val btnListMixer = view.findViewById<MaterialButton>(R.id.btnListMixer)
        val tvMixerList  = view.findViewById<TextView>(R.id.tvMixerList)
        val cardModuleWarning = view.findViewById<android.view.View>(R.id.cardModuleWarning)
        val tvModuleStatus    = view.findViewById<TextView>(R.id.tvModuleStatus)

        // ── Modül kurulu mu kontrol et ────────────────────────────────────
        scope.launch {
            val (installed, detail) = withContext(Dispatchers.IO) { checkAudioModule() }
            if (!installed) {
                cardModuleWarning.visibility = android.view.View.VISIBLE
                tvModuleStatus.text = detail
                // Boost butonlarını devre dışı bırak
                btnApply.isEnabled = false
                btnApply.alpha = 0.4f
                seekBoost.isEnabled = false
                seekBoost.alpha = 0.4f
                tvStatus.text = "⚠ Modül kurulu değil — amplifikasyon devre dışı"
                tvStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
            } else {
                cardModuleWarning.visibility = android.view.View.GONE
            }
        }

        // ── Sistem ses seviyeleri ──────────────────────────────────────────
        fun pct(stream: Int): Int {
            val cur = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            return if (max > 0) cur * 100 / max else 0
        }
        fun applyPct(stream: Int, pct: Int) {
            val max = am.getStreamMaxVolume(stream)
            am.setStreamVolume(stream, (pct * max / 100).coerceIn(0, max), 0)
        }

        seekMedia.progress = pct(AudioManager.STREAM_MUSIC)
        seekRing.progress  = pct(AudioManager.STREAM_RING)
        seekAlarm.progress = pct(AudioManager.STREAM_ALARM)
        tvMedia.text = "${seekMedia.progress}%"
        tvRing.text  = "${seekRing.progress}%"
        tvAlarm.text = "${seekAlarm.progress}%"

        fun simpleListener(stream: Int, tv: TextView) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = "$p%"
                if (fromUser) applyPct(stream, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekMedia.setOnSeekBarChangeListener(simpleListener(AudioManager.STREAM_MUSIC, tvMedia))
        seekRing.setOnSeekBarChangeListener(simpleListener(AudioManager.STREAM_RING, tvRing))
        seekAlarm.setOnSeekBarChangeListener(simpleListener(AudioManager.STREAM_ALARM, tvAlarm))

        // ── Yazılım Amplifikasyonu ─────────────────────────────────────────
        val savedBoost = prefs.getInt("audio_boost_pct", 0)
        seekBoost.progress = savedBoost
        tvBoost.text = boostLabel(savedBoost)

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvBoost.text = boostLabel(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnApply.setOnClickListener {
            val boost = seekBoost.progress
            btnApply.isEnabled = false
            scope.launch {
                tvStatus.text = "Uygulanıyor..."
                tvStatus.setTextColor(requireContext().getColor(R.color.text_secondary))
                val result = withContext(Dispatchers.IO) { applyBoost(requireContext(), am, boost) }
                btnApply.isEnabled = true
                prefs.edit().putInt("audio_boost_pct", boost).apply()
                tvStatus.text = result
                tvStatus.setTextColor(requireContext().getColor(
                    if (result.startsWith("✓")) R.color.accent_green else R.color.accent_orange
                ))
            }
        }

        btnReset.setOnClickListener {
            seekBoost.progress = 0
            tvBoost.text = boostLabel(0)
            scope.launch {
                withContext(Dispatchers.IO) { applyBoost(requireContext(), am, 0) }
                prefs.edit().putInt("audio_boost_pct", 0).apply()
                tvStatus.text = "✓ Normal seviyeye döndü"
                tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
            }
        }

        // Kaydedilmiş boost varsa uygula
        if (savedBoost > 0) {
            scope.launch { withContext(Dispatchers.IO) { applyBoost(requireContext(), am, savedBoost) } }
        }

        // Tanı butonu — audio_policy_volumes.xml ve sistem bilgisi
        btnListMixer.setOnClickListener {
            btnListMixer.isEnabled = false
            tvMixerList.text = "Taranıyor..."
            scope.launch {
                val out = withContext(Dispatchers.IO) { diagAudio() }
                btnListMixer.isEnabled = true
                tvMixerList.text = out
            }
        }
    }

    private fun checkAudioModule(): Pair<Boolean, String> {
        // KSU Next modül dizini
        val ksuPath = "/data/adb/modules/audio_boost"
        val magiskPath = "/data/adb/modules/audio_boost"
        val xmlOverlay = "$ksuPath/system/vendor/etc/audio_policy_volumes.xml"

        val (modExists, _) = RootHelper.runAsRoot("test -d $ksuPath")
        if (!modExists) return Pair(false, "Modül dizini bulunamadı: $ksuPath")

        val (xmlExists, _) = RootHelper.runAsRoot("test -f $xmlOverlay")
        if (!xmlExists) return Pair(false, "Overlay XML bulunamadı: $xmlOverlay")

        // Modül disabled mi?
        val (disabledExists, _) = RootHelper.runAsRoot("test -f $ksuPath/disable")
        if (disabledExists) return Pair(false, "Modül devre dışı bırakılmış")

        // Overlay gerçekten aktif mi? (boot sonrası mount edilmiş olmalı)
        val (_, mountOut) = RootHelper.runAsRoot("cat /proc/mounts")
        val overlayActive = mountOut.contains("audio_boost") || mountOut.contains("overlay")

        return Pair(true, if (overlayActive) "Aktif + overlay mount edilmiş" else "Kurulu (yeniden başlatma gerekebilir)")
    }

    private fun diagAudio(): String {
        val sb = StringBuilder()

        // audio_policy_volumes.xml nerede?
        val xmlPaths = listOf(
            "/vendor/etc/audio_policy_volumes.xml",
            "/system/etc/audio_policy_volumes.xml",
            "/odm/etc/audio_policy_volumes.xml",
            "/vendor/etc/audio/audio_policy_volumes.xml"
        )
        val foundXml = xmlPaths.firstOrNull { RootHelper.runAsRoot("test -f $it").first }
        sb.appendLine("=== audio_policy_volumes.xml: ${foundXml ?: "BULUNAMADI"}")
        if (foundXml != null) {
            val (_, xmlContent) = RootHelper.runAsRoot("cat $foundXml | head -30")
            sb.appendLine(xmlContent.trim())
        }

        // tinymix
        val tinymixPaths = listOf("/system/bin/tinymix", "/vendor/bin/tinymix", "/system/xbin/tinymix")
        val tinymixBin = tinymixPaths.firstOrNull { RootHelper.runAsRoot("test -x $it").first }
        sb.appendLine("\n=== tinymix: ${tinymixBin ?: "BULUNAMADI"}")

        // /proc/asound
        val (_, asoundOut) = RootHelper.runAsRoot("ls /proc/asound/ 2>/dev/null")
        sb.appendLine("=== /proc/asound: ${asoundOut.trim().ifEmpty { "YOK" }}")

        // audioserver durumu
        val (_, psOut) = RootHelper.runAsRoot("ps -A | grep audioserver | head -3")
        sb.appendLine("=== audioserver: ${psOut.trim().ifEmpty { "YOK" }}")

        return sb.toString()
    }

    private fun boostLabel(p: Int): String = when {
        p == 0   -> "%100 (Normal)"
        p <= 50  -> "%${100 + p} (+$p)"
        p <= 100 -> "%${100 + p} (Güçlü)"
        else     -> "%${100 + p} (Maksimum)"
    }

    private fun applyBoost(ctx: Context, am: AudioManager, boostPct: Int): String {
        runCatching { enhancer?.release() }
        enhancer = null

        if (boostPct == 0) {
            RootHelper.runAsRoot("setprop persist.audio.volume.boost 0")
            restoreAudioPolicyVolumes()
            return "✓ Sıfırlandı"
        }

        val results = mutableListOf<String>()

        // Katman 1: Ses seviyesini max'a çek
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            results.add("vol_max")
        } catch (_: Exception) {}

        // Katman 2: audio_policy_volumes.xml — en etkili yöntem
        if (patchAudioPolicyVolumes(boostPct)) results.add("policy_xml")

        // Katman 3: LoudnessEnhancer — AudioTrack ile gerçek session ID al
        val gainMilliDb = (boostPct * 12).coerceIn(100, 1500)
        try {
            val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val sessionId = audioTrack.audioSessionId
            val le = LoudnessEnhancer(sessionId)
            le.setTargetGain(gainMilliDb)
            le.enabled = true
            audioTrack.play()
            enhancer = le
            results.add("le_audiotrack")
        } catch (_: Exception) {
            // Fallback: session 0 dene
            try {
                val le = LoudnessEnhancer(0)
                le.setTargetGain(gainMilliDb)
                le.enabled = true
                enhancer = le
                results.add("le_s0")
            } catch (_: Exception) {}
        }

        // Katman 4: persist property
        RootHelper.runAsRoot("setprop persist.audio.volume.boost $boostPct")

        return if (results.isNotEmpty())
            "✓ ${results.joinToString(" + ")}"
        else
            "⚠ Etki yok — root veya desteklenmiyor"
    }

    /**
     * /vendor/etc/audio_policy_volumes.xml içindeki SPEAKER attenuation değerlerini
     * boost oranına göre düşürür (daha az attenuation = daha yüksek ses).
     * Değişiklik audioserver restart ile aktif olur.
     */
    private fun patchAudioPolicyVolumes(boostPct: Int): Boolean {
        val candidates = listOf(
            "/vendor/etc/audio_policy_volumes.xml",
            "/system/etc/audio_policy_volumes.xml",
            "/odm/etc/audio_policy_volumes.xml",
            "/vendor/etc/audio/audio_policy_volumes.xml"
        )
        val src = candidates.firstOrNull { RootHelper.runAsRoot("test -f $it").first } ?: return false

        // Backup al (bir kez)
        val bak = "$src.bak"
        val (bakExists, _) = RootHelper.runAsRoot("test -f $bak")
        if (!bakExists) RootHelper.runAsRoot("cp $src $bak")

        // Mevcut içeriği oku
        val (_, content) = RootHelper.runAsRoot("cat $src")
        if (content.isBlank()) return false

        // SPEAKER satırlarındaki attenuation değerlerini azalt
        // Örnek: <point>100,-9600</point>  →  daha az negatif değer = daha yüksek ses
        // boostPct=50 → attenuation'ı %50 azalt
        val reductionFactor = 1.0 - (boostPct / 200.0) // max %50 azaltma
        val patched = content.replace(Regex("(<point>)(\\d+),(-\\d+)(</point>)")) { mr ->
            val idx = mr.groupValues[2].toIntOrNull() ?: return@replace mr.value
            val atten = mr.groupValues[3].toIntOrNull() ?: return@replace mr.value
            val newAtten = (atten * reductionFactor).toInt()
            "${mr.groupValues[1]}$idx,$newAtten${mr.groupValues[4]}"
        }

        if (patched == content) return false

        // Geçici dosyaya yaz, sonra kopyala
        val tmp = "/data/local/tmp/audio_policy_volumes_patched.xml"
        val writeOk = RootHelper.runAsRoot("cat > $tmp << 'AUDIOEOF'\n$patched\nAUDIOEOF").first
        if (!writeOk) {
            // Alternatif yazma yöntemi
            RootHelper.runAsRoot("echo '${patched.replace("'", "'\\''")}' > $tmp")
        }
        val (cpOk, _) = RootHelper.runAsRoot("cp $tmp $src && chmod 644 $src")
        if (!cpOk) return false

        // audioserver'ı yeniden başlat
        RootHelper.runAsRoot("killall audioserver 2>/dev/null || true")
        RootHelper.runAsRoot("stop audioserver 2>/dev/null; start audioserver 2>/dev/null || true")

        return true
    }

    private fun restoreAudioPolicyVolumes() {
        val candidates = listOf(
            "/vendor/etc/audio_policy_volumes.xml",
            "/system/etc/audio_policy_volumes.xml",
            "/odm/etc/audio_policy_volumes.xml",
            "/vendor/etc/audio/audio_policy_volumes.xml"
        )
        for (src in candidates) {
            val bak = "$src.bak"
            val (bakExists, _) = RootHelper.runAsRoot("test -f $bak")
            if (bakExists) {
                RootHelper.runAsRoot("cp $bak $src && chmod 644 $src")
                RootHelper.runAsRoot("killall audioserver 2>/dev/null || true")
                RootHelper.runAsRoot("stop audioserver 2>/dev/null; start audioserver 2>/dev/null || true")
                break
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        if (prefs.getInt("audio_boost_pct", 0) == 0) {
            runCatching { enhancer?.release() }
            enhancer = null
        }
    }
}
