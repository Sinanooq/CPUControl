package com.cpucontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val p = context.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)
        val littleMin = p.getInt("little_min", 169000)
        val littleMax = p.getInt("little_max", 2200000)
        val bigMin    = p.getInt("big_min",    300000)
        val bigMax    = p.getInt("big_max",    3200000)
        val primeMin  = p.getInt("prime_min",  300000)
        val primeMax  = p.getInt("prime_max",  3350000)
        val gpuMin    = p.getInt("gpu_min",    125000000)
        val gpuMax    = p.getInt("gpu_max",    1400000000)

        CoroutineScope(Dispatchers.IO).launch {
            delay(20000) // wait for system to settle
            RootHelper.setCpuMinFreq(RootHelper.LITTLE_CORES, littleMin)
            RootHelper.setCpuMaxFreq(RootHelper.LITTLE_CORES, littleMax)
            RootHelper.setCpuMinFreq(RootHelper.BIG_CORES, bigMin)
            RootHelper.setCpuMaxFreq(RootHelper.BIG_CORES, bigMax)
            RootHelper.setCpuMinFreq(listOf(RootHelper.PRIME_CORE), primeMin)
            RootHelper.setCpuMaxFreq(listOf(RootHelper.PRIME_CORE), primeMax)
            RootHelper.setGpuMinFreq(gpuMin)
            RootHelper.setGpuMaxFreq(gpuMax)
        }
    }
}
