package com.cpucontrol

data class CpuProfile(
    val littleMin: Int, val littleMax: Int,
    val bigMin: Int,    val bigMax: Int,
    val primeMin: Int,  val primeMax: Int,
    val gpuMin: Int,    val gpuMax: Int,
    // Hangi çekirdekler kapalı (cpu0 her zaman açık)
    val offlineCores: List<Int> = emptyList()
)

object Profiles {

    // Pil Tasarrufu: little düşük, big/prime kısıtlı, GPU düşük, cpu4-7 kısıtlı
    val PILTasarrufu = CpuProfile(
        littleMin = 169000,   littleMax = 1200000,
        bigMin    = 300000,   bigMax    = 1800000,
        primeMin  = 300000,   primeMax  = 1800000,
        gpuMin    = 125000000, gpuMax   = 400000000,
        offlineCores = listOf(6, 7) // cpu6 ve cpu7 kapalı
    )

    // Dengeli: orta frekanslar, hepsi açık
    val Dengeli = CpuProfile(
        littleMin = 169000,   littleMax = 1800000,
        bigMin    = 300000,   bigMax    = 2400000,
        primeMin  = 300000,   primeMax  = 2400000,
        gpuMin    = 125000000, gpuMax   = 700000000
    )

    // Performans: yüksek frekanslar
    val Performans = CpuProfile(
        littleMin = 800000,   littleMax = 2200000,
        bigMin    = 1000000,  bigMax    = 3200000,
        primeMin  = 1000000,  primeMax  = 3350000,
        gpuMin    = 400000000, gpuMax   = 1400000000
    )

    // Oyun: big/prime max, little orta, GPU max
    val Oyun = CpuProfile(
        littleMin = 500000,   littleMax = 1600000,
        bigMin    = 1200000,  bigMax    = 3200000,
        primeMin  = 1200000,  primeMax  = 3350000,
        gpuMin    = 700000000, gpuMax   = 1400000000
    )

    // Gece: sadece little çekirdekler, big/prime kapalı, GPU düşük
    val Gece = CpuProfile(
        littleMin = 169000,   littleMax = 1000000,
        bigMin    = 300000,   bigMax    = 1200000,
        primeMin  = 300000,   primeMax  = 1200000,
        gpuMin    = 125000000, gpuMax   = 300000000,
        offlineCores = listOf(4, 5, 6, 7)
    )

    val hepsi = mapOf(
        "pil_tasarrufu" to PILTasarrufu,
        "dengeli"       to Dengeli,
        "performans"    to Performans,
        "oyun"          to Oyun,
        "gece"          to Gece
    )

    val isimler = mapOf(
        "pil_tasarrufu" to "Pil Tasarrufu",
        "dengeli"       to "Dengeli",
        "performans"    to "Performans",
        "oyun"          to "Oyun",
        "gece"          to "Gece Modu"
    )

    fun apply(profile: CpuProfile) {
        // Önce kapalı çekirdekleri aç (frekans ayarı için)
        for (cpu in 1..7) {
            if (cpu !in profile.offlineCores) {
                RootHelper.setCpuOnline(cpu, true)
            }
        }
        // Frekansları uygula
        RootHelper.setCpuMinFreq(RootHelper.LITTLE_CORES, profile.littleMin)
        RootHelper.setCpuMaxFreq(RootHelper.LITTLE_CORES, profile.littleMax)
        RootHelper.setCpuMinFreq(RootHelper.BIG_CORES, profile.bigMin)
        RootHelper.setCpuMaxFreq(RootHelper.BIG_CORES, profile.bigMax)
        RootHelper.setCpuMinFreq(listOf(RootHelper.PRIME_CORE), profile.primeMin)
        RootHelper.setCpuMaxFreq(listOf(RootHelper.PRIME_CORE), profile.primeMax)
        RootHelper.setGpuMinFreq(profile.gpuMin)
        RootHelper.setGpuMaxFreq(profile.gpuMax)
        // Kapatılacak çekirdekleri kapat
        for (cpu in profile.offlineCores) {
            RootHelper.setCpuOnline(cpu, false)
        }
    }
}
