package com.cpucontrol

object RootHelper {

    // ── Çekirdek tanımları ──────────────────────────────────────────────────
    val LITTLE_CORES = listOf(0, 1, 2, 3)   // Cortex-A510  maks 2.2 GHz
    val BIG_CORES    = listOf(4, 5, 6)       // Cortex-A715  maks 3.2 GHz
    val PRIME_CORE   = 7                     // Cortex-A715  maks 3.35 GHz

    val GPU_PATH = "/sys/devices/platform/soc/13000000.mali/devfreq/13000000.mali"

    // ── Frekans tabloları ───────────────────────────────────────────────────
    val LITTLE_FREQS = listOf(
        169000, 200000, 250000, 300000, 350000, 400000, 450000, 500000,
        550000, 600000, 650000, 700000, 750000, 800000, 850000, 900000,
        950000, 1000000, 1050000, 1100000, 1150000, 1200000, 1250000,
        1300000, 1350000, 1400000, 1450000, 1500000, 1550000, 1600000,
        1650000, 1700000, 1750000, 1800000, 1850000, 1900000, 1950000,
        2000000, 2050000, 2100000, 2150000, 2200000
    )
    val BIG_FREQS = listOf(
        300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000,
        1100000, 1200000, 1300000, 1400000, 1500000, 1600000, 1700000,
        1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000,
        2500000, 2600000, 2700000, 2800000, 2900000, 3000000, 3100000,
        3200000
    )
    val PRIME_FREQS = listOf(
        300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000,
        1100000, 1200000, 1300000, 1400000, 1500000, 1600000, 1700000,
        1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000,
        2500000, 2600000, 2700000, 2800000, 2900000, 3000000, 3100000,
        3200000, 3250000, 3300000, 3350000
    )
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

    val GOVERNORS = listOf("schedutil", "interactive", "performance", "powersave", "ondemand", "conservative")

    // ── Temel root komutu ───────────────────────────────────────────────────
    fun runAsRoot(cmd: String): Pair<Boolean, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            Pair(p.exitValue() == 0, out + err)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Hata")
        }
    }

    // ── CPU frekans ─────────────────────────────────────────────────────────
    fun setCpuMaxFreq(cores: List<Int>, freqKhz: Int): Boolean {
        var ok = true
        for (cpu in cores) {
            val n = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq"
            if (!runAsRoot("echo $freqKhz > $n").first) ok = false
        }
        return ok
    }
    fun setCpuMinFreq(cores: List<Int>, freqKhz: Int): Boolean {
        var ok = true
        for (cpu in cores) {
            val n = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_min_freq"
            if (!runAsRoot("echo $freqKhz > $n").first) ok = false
        }
        return ok
    }
    fun getCpuMaxFreq(cpu: Int): Int {
        val (_, o) = runAsRoot("cat /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq")
        return o.trim().toIntOrNull() ?: -1
    }
    fun getCpuMinFreq(cpu: Int): Int {
        val (_, o) = runAsRoot("cat /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_min_freq")
        return o.trim().toIntOrNull() ?: -1
    }
    fun getCpuCurFreq(cpu: Int): Int {
        val (_, o) = runAsRoot("cat /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")
        return o.trim().toIntOrNull() ?: -1
    }

    // ── CPU online/offline ──────────────────────────────────────────────────
    fun setCpuOnline(cpu: Int, online: Boolean): Boolean {
        if (cpu == 0) return false
        return runAsRoot("echo ${if (online) 1 else 0} > /sys/devices/system/cpu/cpu$cpu/online").first
    }
    fun isCpuOnline(cpu: Int): Boolean {
        if (cpu == 0) return true
        val (_, o) = runAsRoot("cat /sys/devices/system/cpu/cpu$cpu/online")
        return o.trim() == "1"
    }

    // ── CPU Governor ────────────────────────────────────────────────────────
    fun setGovernor(cores: List<Int>, gov: String): Boolean {
        var ok = true
        for (cpu in cores) {
            val n = "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor"
            if (!runAsRoot("echo $gov > $n").first) ok = false
        }
        return ok
    }
    fun getGovernor(cpu: Int): String {
        val (_, o) = runAsRoot("cat /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor")
        return o.trim().ifEmpty { "—" }
    }
    fun getAvailableGovernors(cpu: Int): List<String> {
        val (_, o) = runAsRoot("cat /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_available_governors")
        return o.trim().split(" ").filter { it.isNotEmpty() }
    }

    // ── GPU ─────────────────────────────────────────────────────────────────
    fun setGpuMaxFreq(freqHz: Int): Boolean = runAsRoot("echo $freqHz > $GPU_PATH/max_freq").first
    fun setGpuMinFreq(freqHz: Int): Boolean = runAsRoot("echo $freqHz > $GPU_PATH/min_freq").first
    fun getGpuMaxFreq(): Int { val (_, o) = runAsRoot("cat $GPU_PATH/max_freq"); return o.trim().toIntOrNull() ?: -1 }
    fun getGpuMinFreq(): Int { val (_, o) = runAsRoot("cat $GPU_PATH/min_freq"); return o.trim().toIntOrNull() ?: -1 }
    fun getGpuCurFreq(): Long { val (_, o) = runAsRoot("cat $GPU_PATH/cur_freq"); return o.trim().toLongOrNull() ?: -1L }

    // ── Pil ─────────────────────────────────────────────────────────────────
    fun getChargeLimit(): Int {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_control_limit",
            "/sys/class/power_supply/battery/constant_charge_current_max"
        )
        for (p in paths) {
            val (ok, o) = runAsRoot("cat $p")
            if (ok) return o.trim().toIntOrNull() ?: -1
        }
        return -1
    }
    fun setChargeLimit(percent: Int): Boolean {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_control_limit",
            "/sys/class/power_supply/battery/charge_stop_threshold"
        )
        for (p in paths) {
            val (ok, _) = runAsRoot("echo $percent > $p")
            if (ok) return true
        }
        return false
    }

    // ── Yenileme hızı ───────────────────────────────────────────────────────
    fun setRefreshRate(rate: Int): Boolean {
        // service call SurfaceFlinger ile zorla
        return runAsRoot("service call SurfaceFlinger 1035 i32 $rate").first
    }

    // ── Ağ optimizasyonu ────────────────────────────────────────────────────
    fun applyTcpOptimization(): Boolean {
        val cmds = listOf(
            "echo bbr > /proc/sys/net/ipv4/tcp_congestion_control",
            "echo 1 > /proc/sys/net/ipv4/tcp_low_latency",
            "echo 0 > /proc/sys/net/ipv4/tcp_slow_start_after_idle",
            "echo 1 > /proc/sys/net/ipv4/tcp_fastopen",
            "echo 4096 87380 16777216 > /proc/sys/net/ipv4/tcp_rmem",
            "echo 4096 65536 16777216 > /proc/sys/net/ipv4/tcp_wmem"
        )
        return cmds.all { runAsRoot(it).first }
    }
    fun resetTcpOptimization(): Boolean {
        val cmds = listOf(
            "echo cubic > /proc/sys/net/ipv4/tcp_congestion_control",
            "echo 0 > /proc/sys/net/ipv4/tcp_low_latency",
            "echo 1 > /proc/sys/net/ipv4/tcp_slow_start_after_idle"
        )
        return cmds.all { runAsRoot(it).first }
    }
    fun setWifiPowerSave(enable: Boolean): Boolean {
        val v = if (enable) "on" else "off"
        return runAsRoot("iw dev wlan0 set power_save $v").first
    }
    fun getTcpCongestion(): String {
        val (_, o) = runAsRoot("cat /proc/sys/net/ipv4/tcp_congestion_control")
        return o.trim().ifEmpty { "—" }
    }

    // ── Sıcaklık ────────────────────────────────────────────────────────────
    fun getCpuTemp(): Int {
        val (_, o) = runAsRoot("cat /sys/class/thermal/thermal_zone0/temp")
        val raw = o.trim().toIntOrNull() ?: return -1
        return if (raw > 1000) raw / 1000 else raw
    }

    // ── Ön plan uygulama ────────────────────────────────────────────────────
    fun getForegroundPackage(): String {
        val (_, o) = runAsRoot("dumpsys activity activities | grep mResumedActivity | head -1")
        val match = Regex("([a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+)").find(o)
        return match?.value ?: ""
    }

    // ── Root kontrolü ───────────────────────────────────────────────────────
    fun checkRoot(): Boolean = runAsRoot("id").first

    // ── SoftAP (root ile cmd wifi) ──────────────────────────────────────────
    fun startSoftAp(ssid: String, pass: String): Pair<Boolean, String> {
        // Android 12+ root ile cmd wifi start-softap çalışıyor
        val (ok, out) = runAsRoot("cmd wifi start-softap \"$ssid\" WPA2 $pass 2 false")
        if (ok || out.contains("started") || out.contains("OK")) return Pair(true, out)
        // Alternatif: settings + svc wifi
        runAsRoot("settings put global wifi_ap_ssid \"$ssid\"")
        runAsRoot("settings put global wifi_ap_passwd \"$pass\"")
        val (ok2, out2) = runAsRoot("svc wifi hotspot enable")
        return Pair(ok2 || out2.isEmpty(), out2)
    }

    fun getApInterface(): String {
        // ap0, wlan1, swlan0 gibi isimler olabilir
        val candidates = listOf("ap0", "wlan1", "swlan0", "wlan0")
        for (iface in candidates) {
            val (ok, _) = runAsRoot("ip link show $iface 2>/dev/null")
            if (ok) {
                // Bu interface'in IP'si var mı veya UP mu?
                val (_, addr) = runAsRoot("ip addr show $iface 2>/dev/null")
                if (addr.contains("192.168.43") || addr.contains("UP")) return iface
            }
        }
        // Fallback: herhangi bir UP interface bul (wlan0 hariç)
        val (_, out) = runAsRoot("ip link show | grep -E '(ap|wlan)[0-9]' | grep 'UP' | awk -F': ' '{print \$2}' | head -1")
        return out.trim().ifEmpty { "ap0" }
    }

    fun getWlanInterface(): String {
        val (_, out) = runAsRoot("ip link show | grep -E 'wlan[0-9]:' | awk -F': ' '{print \$2}' | head -1")
        return out.trim().ifEmpty { "wlan0" }
    }

    fun startDnsmasq(iface: String, subnet: String = "192.168.43"): Boolean {
        // Önce varsa öldür
        runAsRoot("pkill -f dnsmasq 2>/dev/null || true")
        // dnsmasq binary'sini bul
        val bin = listOf("/system/bin/dnsmasq", "/system/xbin/dnsmasq", "/data/local/tmp/dnsmasq")
            .firstOrNull { runAsRoot("test -x $it").first } ?: return false
        val cmd = "$bin --interface=$iface " +
                  "--dhcp-range=${subnet}.2,${subnet}.254,255.255.255.0,1h " +
                  "--dhcp-option=3,${subnet}.1 " +
                  "--dhcp-option=6,8.8.8.8,8.8.4.4 " +
                  "--no-resolv --no-poll --keep-in-foreground &"
        return runAsRoot(cmd).first
    }

    fun stopDnsmasq(): Boolean = runAsRoot("pkill -f dnsmasq 2>/dev/null || true").first

    // ── Tethering (NAT) ─────────────────────────────────────────────────────
    fun getUpstreamInterface(): String {
        // Mobil veri veya WiFi upstream interface'ini bul
        val (_, out) = runAsRoot("ip route show default")
        val match = Regex("dev\\s+(\\S+)").find(out)
        return match?.groupValues?.get(1) ?: "rmnet0"
    }

    fun startP2pNat(p2pIface: String): Pair<Boolean, String> {
        val upstream = getUpstreamInterface()
        val cmds = listOf(
            "echo 1 > /proc/sys/net/ipv4/ip_forward",
            // Önce varsa temizle
            "iptables -t nat -D POSTROUTING -o $upstream -j MASQUERADE 2>/dev/null || true",
            "iptables -D FORWARD -i $p2pIface -o $upstream -j ACCEPT 2>/dev/null || true",
            "iptables -D FORWARD -i $upstream -o $p2pIface -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true",
            // Kural ekle
            "iptables -t nat -A POSTROUTING -o $upstream -j MASQUERADE",
            "iptables -A FORWARD -i $p2pIface -o $upstream -j ACCEPT",
            "iptables -A FORWARD -i $upstream -o $p2pIface -m state --state RELATED,ESTABLISHED -j ACCEPT"
        )
        val ok = cmds.all { runAsRoot(it).first }
        return Pair(ok, "upstream=$upstream p2p=$p2pIface")
    }

    fun stopP2pNat(p2pIface: String): Boolean {
        val upstream = getUpstreamInterface()
        val cmds = listOf(
            "echo 0 > /proc/sys/net/ipv4/ip_forward",
            "iptables -t nat -D POSTROUTING -o $upstream -j MASQUERADE 2>/dev/null || true",
            "iptables -D FORWARD -i $p2pIface -o $upstream -j ACCEPT 2>/dev/null || true",
            "iptables -D FORWARD -i $upstream -o $p2pIface -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true"
        )
        return cmds.all { runAsRoot(it).first }
    }

    fun isP2pNatActive(): Boolean {
        val (_, out) = runAsRoot("iptables -t nat -L POSTROUTING -n")
        return out.contains("MASQUERADE")
    }

    // ── DNS ─────────────────────────────────────────────────────────────────
    fun setDns(dns1: String, dns2: String): Boolean {
        val cmds = listOf(
            "setprop net.dns1 $dns1",
            "setprop net.dns2 $dns2",
            "setprop persist.net.dns1 $dns1",
            "setprop persist.net.dns2 $dns2",
            "ndc resolver setnetdns 100 \"\" $dns1 $dns2"
        )
        var ok = false
        for (cmd in cmds) { if (runAsRoot(cmd).first) ok = true }
        return ok
    }
}
