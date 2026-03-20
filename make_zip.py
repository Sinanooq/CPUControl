import zipfile, os

out = 'CPUControl/AudioVolumeBoost-v0.1-SinanAslan.zip'
base = 'CPUControl/audio_boost_module'

with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(base):
        for file in files:
            full = os.path.join(root, file)
            arcname = os.path.relpath(full, base)
            zf.write(full, arcname)

print('OK:', out)
for name in zipfile.ZipFile(out).namelist():
    print(' ', name)
