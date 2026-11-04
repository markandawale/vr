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
