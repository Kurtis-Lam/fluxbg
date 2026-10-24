# fluxbg

An Android live wallpaper app designed for Huawei phones. Pick a video from your gallery, crop it, trim it on a video-editor style timeline, and use it as your home screen and/or lock screen wallpaper.

Designed for Huawei phones (EMUI 8+ and HarmonyOS versions that run Android APKs, such as HarmonyOS 2 to 4.x). It targets Android 8.0+ (API 26) and works on all Huawei phone lines: Pura, Mate, nova, P, Y and the foldables. It should also work on other Android devices.

## Features

- Pick any video from the gallery
- Crop with a fixed-aspect window matched to your screen: drag to move, pinch to resize
- Trim with a timeline bar: thumbnail strip, draggable in/out handles and a scrub playhead
- Preview playback that loops only the trimmed range
- Separate settings for the home screen, the lock screen, or both
- Still-frame option for lock screens that don't accept live wallpapers
- Crop is adapted automatically to each screen's aspect ratio, including foldables
- Muted, GPU-rendered playback that pauses when the screen is off to save battery

## Build

Requirements: Android Studio, JDK 17 or newer, Android SDK Platform 34.

1. Clone the repo and open it in Android Studio.
2. Let Gradle sync.
3. Choose **Build > Build APK(s)**.
4. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

If the Kotlin compiler daemon crashes, add this to `gradle.properties`:

```
kotlin.compiler.execution.strategy=in-process
org.gradle.jvmargs=-Xmx2048m
```

Keep the project outside cloud-synced folders (Synology Drive, iCloud, Dropbox), which can lock build files.

## Usage

1. Install the APK and open **fluxbg**.
2. Choose **Home screen**, **Lock screen** or **Both** at the top.
3. Tap **Choose a video**.
4. Drag and pinch the crop window over the preview to frame the shot.
5. Drag the handles on the timeline to set the in and out points. Drag anywhere else on the bar to scrub.
6. Tap the play button to preview the loop.
7. Tap **Set as ... wallpaper** and confirm in the system preview.
8. For the lock screen, choose **Lock screen** in the system preview if asked. If your Themes app does not allow live lock screens, tap **Set still frame on lock screen** to use the frame at the playhead.

To change the video, crop or range later, edit it in the app and apply the wallpaper again.

## Known limitations

- Live lock screen support depends on your EMUI or HarmonyOS version. Huawei's Themes app may override third-party live wallpapers on the lock screen. Separate home and lock videos need Android 14 level support (HarmonyOS 4.2 and later); on older systems the lock screen uses the home screen settings.
- HarmonyOS NEXT (5.0 and later) does not run Android APKs and is not supported.
- Looping uses `MediaPlayer` seeking, so there may be a brief hitch at the loop point, especially with long keyframe intervals. Re-encoding the clip with frequent keyframes helps.
- Very high-resolution or long videos use more battery and storage. The selected video is copied into the app's private storage.
- HTML-based animated backgrounds are not supported yet.

## Project structure

```
app/src/main/java/com/me/fluxbg/
  MainActivity.kt           Editor UI: video picker, crop, timeline trim, apply actions
  CropOverlayView.kt        Drag and pinch crop window
  TrimBarView.kt            Timeline with thumbnails, trim handles and playhead
  FluxWallpaperService.kt   Live wallpaper service (home / lock aware)
  VideoRenderer.kt          OpenGL ES renderer for the cropped, trimmed video
  Store.kt                  Per-screen settings storage and crop fitting
```

## License

MIT. Add a `LICENSE` file if you want to publish under it.