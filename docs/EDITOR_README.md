# FFBE Kupo Forge (Android)

v1.5: targets Android 16 (API 36), min Android 8. FFBE-style theme (navy panels, blue buttons with a gold edge, cream text),
browser-style tabs, and the FFBE logo as the launcher icon. Two tabs: **Save Editor** and **Game Patch**.

## Save Editor
Open save and Save edited copy sit at the top (Save edited copy writes a new file and never changes your original;
import the new file from the title screen). Below them are four sections:

1. **Profile.** Name: edits `identity.handle_name`. Same rule as the game's rename: not blank, at most 64 characters,
   no hidden or special characters (Python `str.isprintable()`).
2. **Add Units.** Every unit in the data, sorted by name. Each row shows the awakening range, e.g. `Roselia (3★ ~ 6★)`,
   the unit's first banner (season, date, name) and how many copies the save already has, with an Add button.
   Search by name; filter by base star, top star, season and banner. "Awakening open" lowers the top star to what your
   game has opened (the game gates 6★ at the first Lightning banner, 7★ at the Squall/Rinoa banner); the row then says
   e.g. `6★ locked`. Three ticks above the list apply to each Add: Add Max Level (cap of its star + LB), Add Max
   Awakening (highest open star) and Add Max TMR (100%, claim recorded, TMR item into inventory: equipment 21 /
   materia 22, once per unit copy). Unticked, Add makes one copy the way the game's own `grant_unit` does: base star (like a summon), level 1,
   exp 0, LB 1 when the unit has an LB, no gear, TMR 0, `granted_rarity` = the star, a new epoch-ms instance id above
   every id in use, `counts.unit`/`counts.storage` refreshed, and the master id added to `unit_dictionary`. 
3. **Edit Units.** Select units (or none = all):
   - **Max level**: sets the level cap for the unit's current star (1★ 15, 2★ 30, 3★ 40, 4★ 60, 5★ 80, 6★ 100, 7★ 120) and exp 0. Limit Burst is maxed too unless you untick the checkbox.
   - **Set stars**: 1★ to 7★, or Max. A unit that can't reach the pick goes to the closest star it has.
   - **100% TMR + reward**: sets TMR to 1000 (100%), adds the TMR to the inventory once per unit copy, and saves the claim
     (`0:21:<id>:1` for equipment, `0:22:<id>:1` for materia), matching how the game saves a claimed TMR.
4. **TMR/Equipment.** Search; filter by kind (Weapon, Shield, Headgear, Chest, Accessory, Materia) and type; filter by
   season and banner range; set a count; tap an item to add it. A TMR/STMR's season and banner are its unit's first
   banner in the game's own banner list (gacha_community.json, 369 banners). Seasons follow the game's summon screen:
   S1 to 2017-11-15, S2 from 2017-11-16, S3 from 2020-01-30, S4 from 2021-11-18.

## Game Patch
Adds the speed hook to the game APK on the phone, signs it with this app's key, and installs it. It can also install a
patched APK you saved earlier. Below that is the "Patch List" heading with a collapsed "Speed button" section: a floating
button over the game (needs "Display over other apps", only works with a patched game).

## How the speed patch works
The game's Frida script (`lib/arm64-v8a/libexviustest.js.so`) already hooks `cocos2d::Scheduler::update`, with its speed setting fixed at 1x.
The patch adds `assets/speed_patch.js` to the end of that script. The added code writes the scheduler's time scale (offset 0x24, the same field the
developer's TEST 10x build writes) and listens on 127.0.0.1:27333 for a speed number from the floating button. Before it runs, it checks the
scheduler's machine code. If the game's core library ever changes, the hook turns itself off. Patching an APK that is already patched
replaces the old hook.

## Signing key
The key is `keys/editor.pk8` + `keys/editor.crt`, and the app carries a copy as assets. Every build and every game patch use this key, so
patched game updates install over each other. Keep these two files. Anyone who has the APK has this key, but it only signs your own copy of
the game and this app.
Certificate SHA-256: AC851128CB94C16E099F87B9690514DB74BCEEA104E877B1B014A3E69B0E320C

## Data
`assets/db.json` is built with `tools/make_db.py` from the public GL datamine (github.com/aEnigmatic/ffbe: units, equipment, materia,
limit bursts). FFBE Forever's own tables may differ for a few units.

## Building (no Android SDK needed)
You need JDK 11+ and python3. Put these jars in `../dl`. All of them come from Maven Central:
- `org.robolectric:android-all:14-robolectric-10818077`, saved as `android-all.jar` (used only to compile)
- `com.jakewharton.android.repackaged:dalvik-dx:16.0.1`, saved as `dalvik-dx-16.0.1.jar`

Then run `./build.sh`. `tools/axml.py` encodes the manifest and the adaptive icon XML, `tools/arsc.py` writes a tiny
`resources.arsc` (only the icon: mipmap/ic_launcher, mipmap/ic_fg, color/ic_bg), and `V2Signer.java` signs the APK
(signature scheme v2). `resources.arsc` and the PNGs are stored and 4-byte aligned. `tools/make_icon.py` (needs Pillow)
remakes `icon/fg.png` and `icon/legacy.png` from `icon/logo.png`.

Android 14 to 16 notes: the speed service is a `specialUse` foreground service; Android 15+ draws the app edge to edge, so
the root view is padded by the system bars and keyboard (`fitBars`); the install confirm intent gets
`removeLaunchSecurityProtection()` on Android 16.

`test/` holds desktop checks: `T.java` edits a save, `P.java` patches a game APK and checks the signature with Google's
apksig verifier (`com.android.tools.build:apksig:2.3.0`, used only for the check).
