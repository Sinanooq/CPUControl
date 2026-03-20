# KernelKit — Sistem Özeti

**Cihaz:** Poco X6 Pro 5G / Redmi K70E (`duchamp`)  
**SoC:** MediaTek Dimensity 8300 Ultra (MT6897) · TSMC 4nm · ARMv9  
**GPU:** Mali-G615 MC6  
**Kernel:** KernelSU Next  
**Paket:** `com.cpucontrol`

---

## Mimari

```
MainActivity
├── HomeFragment          — Canlı istatistikler (2s döngü)
├── CpuGpuFragment
│   ├── CpuFragment       — Little/Big/Prime frekans + governor
│   ├── AllCpuFragment    — 8 çekirdek toplu kontrol
│   └── GpuFragment       — Mali devfreq min/maks
├── GameFragment          — Oyun listesi + GameDetectorService
├── ToolsFragment
│   ├── ScreenTimeFragment
│   ├── DozeFragment
│   ├── BloatwareFragment
│   ├── RamFragment
│   ├── NetworkFragment
│   └── AudioFragment
└── MoreFragment
    ├── ProfileFragment   — 5 hazır profil
    ├── BatteryFragment
    ├── AppProfileFragment
    └── AboutFragment
```

---

## Çekirdek Grupları

| Grup   | Çekirdekler | Maks Frekans | Mimari     |
|--------|-------------|--------------|------------|
| Little | cpu0–3      | 2200 MHz     | Cortex-A510|
| Big    | cpu4–6      | 3200 MHz     | Cortex-A715|
| Prime  | cpu7        | 3350 MHz     | Cortex-A715|

---

## Servisler

| Servis                       | Görev                                      |
|------------------------------|--------------------------------------------|
| `ScreenTimeNotificationService` | Ongoing bildirim, 30s güncelleme        |
| `AutoProfileService`         | Pil seviyesine göre otomatik profil geçişi |
| `GameDetectorService`        | Ön plan uygulama izleme, oyun profili      |
| `OverlayService`             | Ekran üstü CPU/GPU/sıcaklık göstergesi     |
| `WifiShareService`           | Root WiFi hotspot + NAT                    |

---

## Bildirim Mimarisi

**Tek yetkili kaynak: `BootReceiver`**

```
ACTION_POWER_CONNECTED   → saveSession() → "Şarj Başladı" bildirimi → servisi başlat
ACTION_POWER_DISCONNECTED → collectData() → özet bildirimi (id=98) → saveSession()
ACTION_BOOT_COMPLETED    → session yoksa başlat → servis → CPU/GPU ayarları
```

`MainActivity` — sadece UI, bildirim/session mantığı yok.

---

## Session Yönetimi

`SharedPreferences("cpu_prefs")` anahtarları:

| Anahtar                | Açıklama                        |
|------------------------|---------------------------------|
| `session_start_wall`   | `System.currentTimeMillis()`    |
| `session_start_elapsed`| `SystemClock.elapsedRealtime()` |
| `session_start_uptime` | `SystemClock.uptimeMillis()`    |

**Deep Sleep formülü:** `elapsedRealtime_delta − uptimeMillis_delta`

---

## sysfs Yolları

```
CPU frekans   /sys/devices/system/cpu/cpuX/cpufreq/scaling_{min,max,cur}_freq
CPU governor  /sys/devices/system/cpu/cpuX/cpufreq/scaling_governor
CPU online    /sys/devices/system/cpu/cpuX/online
GPU devfreq   /sys/devices/platform/soc/13000000.mali/devfreq/13000000.mali/
CPU sıcaklık  /sys/class/thermal/thermal_zone0/temp
TCP           /proc/sys/net/ipv4/tcp_congestion_control
TTL           /proc/sys/net/ipv4/ip_default_ttl
```

> `/vendor` erofs mount — yazılamaz.

---

## Önemli Notlar

- Tüm değişiklikler yeniden başlatmada sıfırlanır — `BootReceiver` ile kalıcı hale getirilir.
- `cpu0` hiçbir zaman kapatılamaz.
- Bloatware: `pm disable-user` ile dondurulur, silinmez — geri alınabilir.
- Ses amplifikasyonu `LoudnessEnhancer` API ile yapılır; %150 üzeri hoparlör hasarı riski taşır.
- TTL fix operatör politikasına aykırı olabilir.
