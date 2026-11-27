# FFBE Forever - Kupo Forge

Android save editor plus in-game mods (Gambit, Chamber sort, Quick party, cloud sync and more) for FFBE Forever (`com.exvius.offline.forever`).

AI-assisted. The FFBE Forever developer is not responsible for fixing bugs caused by this tool. Always back up your save.

- Downloads: see the Releases page of this repo.
- Source: `src/` (app), `assets/` (in-game script `quick_patch.js`, data), `build.sh` (build).
- Docs: `docs/EDITOR_README.md` (editor guide), `docs/loader/` (the public loader and its guide).

## Build
`bash build.sh` needs a JDK 11+, python3 and the Android `android-all` and `dalvik-dx` jars in `../dl`.
Signing keys are NOT in this repo (`keys/`, `assets/editor.pk8`, `assets/editor.crt`) and neither is the script signing key. Without them you can compile, but a signed release APK and the signed Install Script payload need your own keys.
