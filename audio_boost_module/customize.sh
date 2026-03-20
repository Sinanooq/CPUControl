#!/system/bin/sh
# Audio Volume Boost Module
# Author: Sinan Aslan
# Version: v0.1
# Magisk / KSU / KSU Next uyumlu

SKIPUNZIP=1

ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ui_print "  Audio Volume Boost v0.1"
ui_print "  by Sinan Aslan"
ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ui_print ""
ui_print "- audio_policy_volumes.xml override"
ui_print "- Speaker: +6dB amplifikasyon"
ui_print "- Magisk / KSU / KSU Next uyumlu"
ui_print ""

# Dosyaları modül dizinine çıkar
unzip -o "$ZIPFILE" "system/*" -d "$MODPATH" >&2

# İzinleri ayarla
set_perm_recursive "$MODPATH/system" root root 0644 0644

ui_print "✓ Kurulum tamamlandı!"
ui_print "  Cihazı yeniden başlatın."
ui_print ""
