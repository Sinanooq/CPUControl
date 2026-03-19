package com.cpucontrol

object RootHelper {

    // Dimensity 8300 Ultra — 4+3+1 (TSMC 4nm, ARMv9)
    // Little : cpu0-3  — Cortex-A510, max 2.2 GHz
    // Big    : cpu4-6  — Cortex-A715, max 3.2 GHz
    // Prime  : cpu7    — Cortex-A715, max 3.35 GHz
    // GPU    : Mali-G615 MC6, max ~1400 MHz
    val LITTLE_CORES = listOf(0, 1, 2, 3)
    val BIG_CORES    = listOf(4, 5, 6)
    val PRIME_CORE   = 7

    val GPU_PATH = "/sys/devices/platform/soc/13000000.mali/devfreq/13000000.mali"

    // Cortex-A510 OPP table (kHz) — 169 MHz base, 2200 MHz max
    val LITTLE_FREQS = listOf(
        169000, 200000, 250000, 300000, 350000, 400000, 450000, 500000,
        550000, 600000, 650000, 700000, 750000, 800000, 850000, 900000,
        950000, 1000000, 1050000, 1100000, 1150000, 1200000, 1250000,
        1300000, 1350000, 1400000, 1450000, 1500000, 1550000, 1600000,
        1650000, 1700000, 1750000, 1800000, 1850000, 1900000, 1950000,
        2000000, 2050000, 2100000, 2150000, 2200000
    )

    // Cortex-A715 OPP table (kHz) — 300 MHz base, 3200 MHz max
    val BIG_FREQS = listOf(
        300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000,
        1100000, 1200000, 1300000, 1400000, 1500000, 1600000, 1700000,
        1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000,
        2500000, 2600000, 2700000, 2800000, 2900000, 3000000, 3100000,
        3200000
    )

    // Cortex-A715 Prime OPP table (kHz) — 300 MHz base, 3350 MHz max
    val PRIME_FREQS = listOf(
        300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000,
        1100000, 1200000, 1300000, 1400000, 1500000, 1600000, 1700000,
        1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000,
        2500000, 2600000, 2700000, 2800000, 2900000, 3000000, 3100000,
        3200000, 3250000, 3300000, 3350000
    )

    // Mali-G615 MC6 GPU OPP table (Hz)
    val GPU_FREQS = listOf(
        125000000, 150000000, 175000000, 200000000, 225000000, 250000000,
        275000000, 300000000, 325000000, 350000000, 375000000, 400000000,
        425000000, 450000000, 475000000, 500000000, 525000000, 550000000,
        575000000, 600000000, 625000000, 650000000, 675000000, 700000000,
        725000000, 750000000, 775000000, 800000000, 825000000, 850000000,
        875000000, 900000000, 925000000, 950000000, 975000000, 1000000000,
        1050000000, 1100000000, 1150000000, 1200000000, 1250000000,
        1300000000, 1350000000, 1400000000
    )

    fun runAsRoot(cmd: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText()
            val error  = process.errorStream.bufferedReader().readText()
            process.waitFor()
            Pair(process.exitValue() == 0, output + error)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    fun setCpuMaxFreq(cores: List<Int>, freqKhz: Int): Boolean {
        var success = true
        for (cpu in cores) {
            val node = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq"
            val (ok, _) = runAsRoot("chmod 644 $node && echo $freqKhz > $node")
            if (!ok) success = false
        }
        return success
    }

    fun setCpuMinFreq(cores: List<Int>, freqKhz: Int): Boolean {
        var success = true
        for (cpu in cores) {
            val node = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_min_freq"
            val (ok, _) = runAsRoot("chmod 644 $node && echo $freqKhz > $node")
            if (!ok) success = false
        }
        return success
    }

    fun setGpuMaxFreq(freqHz: Int): Boolean {
        val (ok, _) = runAsRoot("echo $freqHz > $GPU_PATH/max_freq")
        return ok
    }

    fun setGpuMinFreq(freqHz: Int): Boolean {
        val (ok, _) = runAsRoot("echo $freqHz > $GPU_PATH/min_freq")
        return ok
    }

    fun getCpuMaxFreq(cpu: Int): Int {
        val node = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq"
        val (_, out) = runAsRoot("cat $node")
        return out.trim().toIntOrNull() ?: -1
    }

    fun getCpuMinFreq(cpu: Int): Int {
        val node = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_min_freq"
        val (_, out) = runAsRoot("cat $node")
        return out.trim().toIntOrNull() ?: -1
    }

    fun getGpuMaxFreq(): Int {
        val (_, out) = runAsRoot("cat $GPU_PATH/max_freq")
        return out.trim().toIntOrNull() ?: -1
    }

    fun getGpuMinFreq(): Int {
        val (_, out) = runAsRoot("cat $GPU_PATH/min_freq")
        return out.trim().toIntOrNull() ?: -1
    }

    fun checkRoot(): Boolean {
        val (ok, _) = runAsRoot("id")
        return ok
    }
}
