# PanoPlay Android project — Vivo T4x video-output fix

This project includes a playback-output change intended to address the reported symptom where audio and the playback timer advance but the video area stays black.

## What changed in this build

- **Flat 2D mode now uses Media3 `PlayerView` with a standard Android `SurfaceView` output.** It no longer depends on the custom OpenGL external-texture renderer for ordinary playback.
- **180° and 360° modes still use the existing custom OpenGL renderer** and its monoscopic equirectangular projection.
- The Media3 UI dependency was added and app version was raised to 1.0.1 / versionCode 2.

This is a targeted video-output fix and diagnostic step. It does **not** add SBS stereoscopic rendering or the requested draggable 2D 180° window yet. The current VR projection modes remain monoscopic.

## Build on GitHub Actions

1. Replace the files in your repository with the corresponding files from this project, keeping the same folder paths.
2. Commit the changes to `main`.
3. Open **Actions → Build PanoPlay APK** and wait for the run to finish.
4. Download the `PanoPlay-debug-apk` artifact and install `app-debug.apk` on the Vivo T4x.
5. Test with **FLAT 2D** selected first. The expected test result is that a normal video picture appears, not just audio.

## Changed/new files

- `app/src/main/java/com/example/panoplay/MainActivity.kt`
- `app/build.gradle.kts`
- `app/src/main/res/layout/video_player.xml` (new)

The existing `VrVideoSurfaceView.kt` and GitHub Actions workflow are retained from the previous rendering-fix project.

## v1.1.0: "Dusk" interface redesign

A ground-up restyle of the whole UI. Playback, the OpenGL renderer, gyro motion, audio-sync, gestures and
metadata extraction are untouched.

- **Look:** indigo night, one sunrise-amber accent for anything active or actionable, Inter typography
  (bundled, SIL OFL, Latin subset in `assets/fonts`), custom line-icon set (no emoji/text glyphs).
- **Empty state:** a slowly turning wireframe sphere with the horizon in amber and a viewfinder.
- **View controls:** segmented selectors with drawn field-of-view glyphs for Flat / 180 / 360 and
  Mono / Left eye / Right eye. Eye choice dims when it does not apply (Flat).
- **Stage:** rounded corners are painted over the video (the video surface itself is never clipped).
- **Fullscreen:** glass transport and dock, fading controls, display-cutout aware, in-app speed picker.
- **Sheets:** settings, video details and the error log are now bottom sheets with a custom switch and a
  centre-origin audio-sync slider.

### New / changed files
- `Theme.kt`, `Glyphs.kt`, `Widgets.kt` (new): design tokens, icon set, custom views
- `MainActivity.kt`: UI layer rewritten, playback logic unchanged
- `res/values/styles.xml`, `res/anim/sheet_*.xml`: theme and bottom-sheet style
- `assets/fonts/*`: Inter (see `LICENSE-Inter.txt`)
- `app/build.gradle.kts`: version 1.1.0 / code 3
