#!/bin/sh
# Build FFBE-Kupo-Forge.apk without the Android SDK.
# Needs: JDK 11+, python3, and in ../dl: android-all-14 jar (compile only), dalvik-dx-16.0.1.jar.
set -e
cd "$(dirname "$0")"
VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
OUT=build/FFBE-Kupo-Forge-v$VER.apk
DL=${DL:-../dl}
rm -rf cls build cls-sign && mkdir -p cls build cls-sign
# 1.x = stable line; 0.x = experimental (same code plus the experimental in-game patches (Quick party, Unit label).
# From 1.15 on both lines carry the same features; experimental (0.x) is where new, untested work goes first.
EXP=true
BOT=true   # from 1.15 the stable line carries the bot, Bestiary, Missions and Summon too
LOGS=true
case "$VER" in 1.*) LOGS=false;; esac   # stable leaves the logs out
export EXP BOT LOGS
APPVER=$VER
export APPVER
sed -i "s/EXP = [a-z]*;/EXP = $EXP;/; s/BOT = [a-z]*;/BOT = $BOT;/; s/LOGS = [a-z]*;/LOGS = $LOGS;/" src/com/ffbeforever/saveedit/Flags.java
javac -nowarn -Xlint:none --release 8 -encoding UTF-8 -cp "$DL/android-all.jar" -d cls src/com/ffbeforever/saveedit/*.java
java -cp "$DL/dalvik-dx-16.0.1.jar" com.android.dx.command.Main --dex --min-sdk-version=26 --output=build/classes.dex cls
python3 -I tools/axml.py AndroidManifest.xml build/AndroidManifest.xml
python3 -I tools/axml.py icon/ic_launcher.xml build/ic_launcher.xml
python3 -I tools/arsc.py build/resources.arsc
python3 -I - <<'PY2'
import zipfile
# resources.arsc must be stored and 4-byte aligned (Android 11+ refuses the install otherwise).
z = zipfile.ZipFile('build/unsigned.apk', 'w', zipfile.ZIP_DEFLATED)
def put(src, name, stored=False):
    data = open(src, 'rb').read()
    zi = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    if stored:
        off = z.fp.tell() + 30 + len(name.encode())
        pad = (4 - (off + 4) % 4) % 4          # 4-byte extra header + padding
        zi.extra = b'\x35\xd9' + (pad + 0).to_bytes(2, 'little') + b'\0' * pad
    z.writestr(zi, data)
put('build/AndroidManifest.xml', 'AndroidManifest.xml')
put('build/resources.arsc', 'resources.arsc', True)
put('build/classes.dex', 'classes.dex')
put('build/ic_launcher.xml', 'res/mipmap-anydpi-v26/ic_launcher.xml')
put('icon/fg.png', 'res/mipmap-nodpi/ic_fg.png', True)
put('icon/legacy.png', 'res/mipmap-nodpi/ic_launcher.png', True)
import os
extra = []
if os.environ.get('EXP') == 'true':
    q = open('assets/quick_patch.js', encoding='utf-8').read()
    if os.environ.get('BOT') != 'true':      # stable: cut the battle bot test code out of the hook file
        a = q.index('  // ---------- Battle bot test'); b = q.index('  function cloudStart() {')
        q = q[:a] + q[b:]
    open('build/quick_patch.js', 'w', encoding='utf-8').write(q)
    extra = ['quick_icon.png']
    put('build/quick_patch.js', 'assets/quick_patch.js')
    # Signed script for the in-app "Install Script" button. Only the finished signed file goes in the app; the private key
    # stays in the loader folder outside the app and is used here, at build time, on this machine only.
    import subprocess
    ldir = os.environ.get('KUPO_LOADER_DIR', '/mnt/project-files/ffbe/loader')
    bot = '1' if os.environ.get('BOT') == 'true' else '0'
    ver = os.environ.get('APPVER', '')
    # The hooks are inside a function that starts when the app sends the player's Patch List choices ("opt <json>" on the game's
    # answer port), or by itself after 20 s with everything off (title brand and moogle button only). Signed text cannot hold the choices.
    stub = ("globalThis.FFBE_CMD = globalThis.FFBE_CMD || {};\n"
            "globalThis.__kupoStarted = false;\n"
            "globalThis.__kupoVer = '%s';\n"
            "globalThis.__kupoOptFile = '/data/data/com.exvius.offline.forever/files/kupo_opt.json';\n"
            "globalThis.__kupoFile = function (w, t) {\n"
            "  try {\n"
            "    var libc = Process.getModuleByName('libc.so');\n"
            "    var fopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);\n"
            "    var fclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);\n"
            "    var f = fopen(Memory.allocUtf8String(globalThis.__kupoOptFile), Memory.allocUtf8String(w ? 'w' : 'r'));\n"
            "    if (f.isNull()) return null;\n"
            "    var out = '';\n"
            "    if (w) { var b = Memory.allocUtf8String(t); var fw = new NativeFunction(libc.getExportByName('fwrite'), 'int', ['pointer', 'int', 'int', 'pointer']); fw(b, 1, t.length, f); }\n"
            "    else { var fr = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']); var m = Memory.alloc(8192); var n = fr(m, 1, 8000, f); out = n > 0 ? m.readUtf8String(n) : ''; }\n"
            "    fclose(f); return out;\n"
            "  } catch (e) { return null; }\n"
            "};\n"
            "globalThis.__kupoGo = function (o) {\n"
            "  if (globalThis.__kupoStarted) return 'ok already started';\n"
            "  globalThis.__kupoStarted = true;\n"
            "  globalThis.FFBE_OPT = o || {};\n"
            "  try { globalThis.__kupoStart(); } catch (e) { try { console.log('FFBE start failed: ' + e); } catch (x) { } return 'error ' + e; }\n"
            "  return 'ok started';\n"
            "};\n"
            "globalThis.FFBE_CMD.opt = function (t) {\n"
            "  var txt = t.substring(4).trim(), o;\n"
            "  try { o = JSON.parse(txt); } catch (e) { return 'error bad options'; }\n"
            "  var old = globalThis.__kupoFile(false);\n"
            "  if (old !== txt) globalThis.__kupoFile(true, txt);\n"
            "  if (globalThis.__kupoStarted) { var ap = ''; try { if (globalThis.__kupoApply) ap = globalThis.__kupoApply(o); } catch (e) { ap = 'error ' + e; } return 'ok ' + (old === txt ? 'already started' : 'saved') + ' ' + ap; }\n"
            "  return globalThis.__kupoGo(o);\n"
            "};\n"
            "globalThis.FFBE_CMD.optq = function () { return 'optq ' + (globalThis.__kupoStarted ? 1 : 0) + ' ' + globalThis.__kupoVer; };\n"
            "(function () {\n"
            "  var saved = globalThis.__kupoFile(false), o = null;\n"
            "  if (saved) { try { o = JSON.parse(saved); } catch (e) { o = null; } }\n"
            "  setTimeout(function () { globalThis.__kupoGo(o || { ver: globalThis.__kupoVer, speeds: [1, 2, 3, 5, 10] }); }, o ? 300 : 20000);\n"
            "})();\n" % ver)
    body = ("if (globalThis.FFBE_SETSPEED) { console.log('FFBE script already installed'); } else {\n"
            + open('assets/early_patch.js', encoding='utf-8').read() + "\n;\n"
            + open('assets/speed_patch.js', encoding='utf-8').read() + "\n;\n" + stub
            + "globalThis.__kupoStart = function () {\n" + q + "\n};\n}\n")
    open('build/kupo_script.js', 'w', encoding='utf-8').write(body)
    if os.path.exists(ldir + '/private/kupo_sign_private.pem'):
        subprocess.check_call(['sh', ldir + '/sign_payload.sh', 'build/kupo_script.js', 'build/kupo_payload.bin', 'FFBE Kupo Forge', '90', '*'])
        put('build/kupo_payload.bin', 'assets/kupo_payload.bin')
        # same body, but the header asks the newer game build to keep the script between starts (keep=1)
        subprocess.check_call(['sh', ldir + '/sign_payload.sh', 'build/kupo_script.js', 'build/kupo_payload_keep.bin', 'FFBE Kupo Forge', '90', '*', '1'])
        put('build/kupo_payload_keep.bin', 'assets/kupo_payload_keep.bin')
    else:
        print('NOTE: no signing key at ' + ldir + '/private: Install Script payload NOT included')
for f in extra + ['db.json', 'bestiary.json', 'gskills.json', 'lb_icon.png', 'dual_icon.png', 'summon_icon.png', 'geardata.json', 'awaken_mats.json', 'mascot_head.png', 'mascot_float.png', 'speed_patch.js', 'editor.pk8', 'editor.crt', 'gp/classes7.dex', 'gp/classes8.dex', 'gp/classes7.sha256', 'icons.bin']:
    put('assets/' + f, 'assets/' + f)
z.close()
z = zipfile.ZipFile('build/unsigned.apk')
for i in z.infolist():
    if i.compress_type == 0:
        start = i.header_offset + 30 + len(i.filename.encode()) + len(i.extra)
        assert start % 4 == 0, (i.filename, start)
PY2
javac -nowarn --release 8 -d cls-sign src/com/ffbeforever/saveedit/V2Signer.java tools/SignApk.java
java -cp cls-sign SignApk build/unsigned.apk "$OUT" keys/editor.pk8 keys/editor.crt
rm build/unsigned.apk
echo "Built $OUT"
