# PanoPlay — Android video viewer

PanoPlay is a phone-first Android video player. It plays on the phone's ordinary 2D display; a VR headset is not required.

## Included in this starter app

- **Flat 2D** playback for regular videos, including portrait videos like the supplied reference screenshot. The picture is fitted without stretching or cropping.
- **180°** and **360°** monoscopic equirectangular viewing, rendered onto an internal sphere with OpenGL ES.
- Drag with one finger to look around; pinch with two fingers to zoom the view.
- Local video selection through Android's document picker, with no broad storage permission.
- Play/pause, ±10-second seek, timeline scrubber, and portrait/landscape screen rotation.
- Media decoding and playback powered by AndroidX Media3 ExoPlayer.

## Open and run

1. Install **Android Studio** with Android SDK Platform 36 and a Java 17 JDK.
2. Open the `PanoPlay` folder in Android Studio.
3. Allow Gradle to sync and download dependencies.
4. Connect an Android phone (Android 7.0 / API 24 or newer) or start an emulator.
5. Press **Run**. To build a debug APK from a terminal instead, run `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`). The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
6. Tap **OPEN VIDEO** and choose an MP4/MKV or another video supported by the device's Android media decoders.
7. Choose **FLAT 2D**, **180°**, or **360°**.

The Gradle project uses Android Gradle Plugin 8.13.2, Gradle 8.13, Kotlin 2.2.20, and Media3 1.11.1. The included `gradlew` bootstrap script downloads Gradle 8.13 on first use if it is not already cached. The Media3 version is declared in `app/build.gradle.kts`.

## Important video-format notes

- Use **FLAT 2D** for ordinary portrait/landscape clips and for videos already framed like the supplied screenshot.
- Use **180°** or **360°** for **monoscopic equirectangular** video, with the panorama encoded across the video frame. 180° maps the video across the front half of the viewing sphere; 360° maps it around the full sphere.
- This first version does **not** split stereoscopic VR formats (side-by-side / top-bottom) into separate eyes, and it does not auto-detect a video's projection. Select the projection that matches the source file.
- Video codec and resolution support still depend on the Android device's hardware/software decoders.

## Project structure

```text
PanoPlay/
├── app/
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/java/com/example/panoplay/
│   │   ├── MainActivity.kt
│   │   └── VrVideoSurfaceView.kt
│   └── build.gradle.kts
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Implementation overview

Media3 sends decoded frames to a `Surface` backed by `SurfaceTexture`. The `GLSurfaceView` samples that external OES texture and draws either a fitted 2D quad or an equirectangular mesh. The app's VR modes are monoscopic 2D viewports into the panorama, not a stereoscopic headset mode.

## Build the APK using only an Android phone (no computer)

This project includes a GitHub Actions cloud-build workflow at `.github/workflows/build-apk.yml`.

1. On your phone, create/sign in to a free account at https://github.com.
2. Create a **Public** repository named `PanoPlay` (public repositories allow GitHub Actions on the free plan).
3. Extract the project ZIP using your phone's Files app.
4. In the repository, use **Add file → Upload files** and upload the extracted project contents, including the hidden `.github/workflows/build-apk.yml` file. If your phone's browser hides dot-folders, use GitHub's web editor to create `.github/workflows/build-apk.yml` and paste the workflow content from the project ZIP.
5. Commit the files to the `main` branch. GitHub Actions should start the build automatically.
6. Open the repository's **Actions** tab, open the latest **Build PanoPlay APK** run, and wait for it to finish successfully.
7. Under **Artifacts**, tap `PanoPlay-debug-apk` to download the ZIP. Extract it on your phone; inside is `app-debug.apk`.
8. Tap the APK to install. Android may ask you to allow installation from that browser or file manager; enable that permission only for the app you used to download/open the APK.

The cloud workflow creates an unsigned-for-release **debug APK** suitable for personal testing. Do not distribute it as a production release. If GitHub asks to enable Actions, enable them for this repository.
