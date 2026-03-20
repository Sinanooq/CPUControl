package com.cpucontrol

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val CHANNEL_ID = "overlay_service"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(3, buildNotif())
        showOverlay()
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_stats, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 120
        }

        // Sürüklenebilir overlay
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f

        overlayView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(overlayView, params)
        startUpdating()
    }

    private fun startUpdating() {
        val tvCpu  = overlayView?.findViewById<TextView>(R.id.tvOverlayCpu)
        val tvGpu  = overlayView?.findViewById<TextView>(R.id.tvOverlayGpu)
        val tvTemp = overlayView?.findViewById<TextView>(R.id.tvOverlayTemp)
        val tvBat  = overlayView?.findViewById<TextView>(R.id.tvOverlayBat)

        scope.launch {
            while (true) {
                val cpu  = withContext(Dispatchers.IO) { RootHelper.getCpuCurFreq(7) }
                val gpu  = withContext(Dispatchers.IO) { RootHelper.getGpuCurFreq() }
                val temp = withContext(Dispatchers.IO) { RootHelper.getCpuTemp() }
                val bat  = getBatteryLevel()

                tvCpu?.text  = "CPU: ${if (cpu > 0) "${cpu / 1000} MHz" else "—"}"
                tvGpu?.text  = "GPU: ${if (gpu > 0) "${gpu / 1_000_000} MHz" else "—"}"
                tvTemp?.text = "TEMP: ${if (temp > 0) "${temp}°C" else "—"}"
                tvBat?.text  = "BAT: ${bat}%"

                delay(2_000)
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Overlay Servisi", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CPU Kontrol")
            .setContentText("Overlay aktif")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        overlayView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }
}
