#!/bin/sh
# Rebuilds assets/gp/* (game-side folder-picker patch). Needs baksmali/smali 2.5.2 jars (+deps) in $SM, original classes7.dex of the game.
# usage: SM=/tmp/sm ORIG=classes7.dex ./build_gp.sh
set -e
cd "$(dirname "$0")"
rm -rf /tmp/gp_out && java -cp "$SM/*" org.jf.baksmali.Main d -a 34 -o /tmp/gp_out "$ORIG"
python3 patch_smali.py /tmp/gp_out/com/exvius/local/LocalGameBridge.smali
java -cp "$SM/*" org.jf.smali.Main a -a 24 /tmp/gp_out -o ../assets/gp/classes7.dex
rm -rf cls && mkdir cls
javac -nowarn -Xlint:none --release 8 -encoding UTF-8 -cp ../../dl/android-all.jar -d cls src/com/ffbeforever/patch/*.java
java -cp ../../dl/dalvik-dx-16.0.1.jar com.android.dx.command.Main --dex --min-sdk-version=24 --output=../assets/gp/classes8.dex cls
sha256sum "$ORIG" | cut -d' ' -f1 > ../assets/gp/classes7.sha256
