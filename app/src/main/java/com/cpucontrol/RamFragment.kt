package com.cpucontrol

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class RamFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_ram, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvTotal      = view.findViewById<TextView>(R.id.tvRamTotal)
        val tvUsed       = view.findViewById<TextView>(R.id.tvRamUsed)
        val tvFree       = view.findViewById<TextView>(R.id.tvRamFree)
        val tvCached     = view.findViewById<TextView>(R.id.tvRamCached)
        val progressRam  = view.findViewById<ProgressBar>(R.id.progressRam)
        val btnClear     = view.findViewById<MaterialButton>(R.id.btnClearCache)
        val tvStatus     = view.findViewById<TextView>(R.id.tvCacheStatus)
        val tvZramSize   = view.findViewById<TextView>(R.id.tvZramSize)
        val tvZramUsed   = view.findViewById<TextView>(R.id.tvZramUsed)

        loadRamInfo(tvTotal, tvUsed, tvFree, tvCached, progressRam)
        loadZramInfo(tvZramSize, tvZramUsed)

        btnClear.setOnClickListener {
            btnClear.isEnabled = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    RootHelper.runAsRoot("echo 3 > /proc/sys/vm/drop_caches").first
                }
                btnClear.isEnabled = true
                if (ok) {
                    tvStatus.text = "Önbellek temizlendi"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                    delay(500)
                    loadRamInfo(tvTotal, tvUsed, tvFree, tvCached, progressRam)
                } else {
                    tvStatus.text = "Temizleme başarısız"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
            }
        }

        // 10 saniyede bir güncelle
        scope.launch {
            while (true) {
                delay(10_000)
                loadRamInfo(tvTotal, tvUsed, tvFree, tvCached, progressRam)
                loadZramInfo(tvZramSize, tvZramUsed)
            }
        }
    }

    private fun loadRamInfo(
        tvTotal: TextView, tvUsed: TextView, tvFree: TextView,
        tvCached: TextView, progress: ProgressBar
    ) {
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                val (_, raw) = RootHelper.runAsRoot("cat /proc/meminfo")
                parseMemInfo(raw)
            }
            val totalMb  = info["MemTotal"] ?: 0L
            val freeMb   = info["MemFree"] ?: 0L
            val availMb  = info["MemAvailable"] ?: 0L
            val cachedMb = info["Cached"] ?: 0L
            val usedMb   = totalMb - availMb

            tvTotal.text  = "${totalMb} MB toplam"
            tvUsed.text   = "$usedMb MB"
            tvFree.text   = "$availMb MB"
            tvCached.text = "Önbellek: $cachedMb MB"

            val pct = if (totalMb > 0) ((usedMb * 100) / totalMb).toInt() else 0
            progress.progress = pct

            val color = when {
                pct >= 85 -> requireContext().getColor(R.color.accent_orange)
                pct >= 70 -> requireContext().getColor(R.color.accent_yellow)
                else      -> requireContext().getColor(R.color.accent_cyan)
            }
            progress.progressTintList = android.content.res.ColorStateList.valueOf(color)
            tvUsed.setTextColor(color)
        }
    }

    private fun loadZramInfo(tvSize: TextView, tvUsed: TextView) {
        scope.launch {
            val (sizeMb, usedMb) = withContext(Dispatchers.IO) {
                val (_, sizeRaw) = RootHelper.runAsRoot("cat /sys/block/zram0/disksize")
                val (_, usedRaw) = RootHelper.runAsRoot("cat /sys/block/zram0/mem_used_total")
                val size = sizeRaw.trim().toLongOrNull()?.div(1024 * 1024) ?: 0L
                val used = usedRaw.trim().toLongOrNull()?.div(1024 * 1024) ?: 0L
                Pair(size, used)
            }
            tvSize.text = if (sizeMb > 0) "$sizeMb MB" else "—"
            tvUsed.text = if (usedMb > 0) "$usedMb MB" else "—"
        }
    }

    private fun parseMemInfo(raw: String): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        raw.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val key = parts[0].trimEnd(':')
                val kb  = parts[1].toLongOrNull() ?: return@forEach
                map[key] = kb / 1024L  // kB → MB
            }
        }
        return map
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
