# fluxbg

An Android live wallpaper app designed for Huawei phones. Pick a video from your gallery, crop it, trim it on a video-editor style timeline, and use it as your home screen and/or lock screen wallpaper.

Designed for Huawei phones (EMUI 8+ and HarmonyOS versions that run Android APKs, such as HarmonyOS 2 to 4.x). It targets Android 8.0+ (API 26) and works on all Huawei phone lines: Pura, Mate, nova, P, Y and the foldables. It should also work on other Android devices.

## Features

- Home screen is a library of every video you have added, with thumbnails and durations
- Add a video from the gallery, then go straight to the editor
- Crop with a fixed-aspect window matched to your screen: drag to move, pinch to resize
- Trim with a timeline bar: thumbnail strip, draggable in/out handles and a scrub playhead
- Preview playback that loops only the trimmed range
- Separate settings for the home screen, the lock screen, or both
- Lock screen option that sets the frame at the playhead, with a fallback to the system Set as sheet
- Crop is adapted automatically to each screen's aspect ratio, including foldables
- Muted, GPU-rendered playback that pauses when the screen is off to save battery

## Get the APK

There are two ways to get the app:

1. **Download a prebuilt APK** from the [`apks/`](apks/) folder in this repo. This is the quickest option, but the prebuilt APKs are not rebuilt on every commit, so they **might not include the latest updates**.
2. **Build it yourself** from the source (see [Build](#build) below). This is the way to get the latest code changes.

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

1. Install the APK (downloaded from `apks/` or built yourself) and open **fluxbg**.
3. On the home screen, tap **Add video** and pick a clip, or tap a video you added earlier. Hold a video to delete it.
4. In the editor, drag and pinch the crop window over the preview to frame the shot.
5. Drag the handles on the timeline to set the in and out points. Drag anywhere else on the bar to scrub.
6. Tap the play button to preview the loop.
7. Under **Apply to**, choose **Home screen**, **Lock screen** or **Both**, then tap the main button and confirm in the system preview.
8. For the lock screen, the main button sets the frame at the playhead as your lock screen picture. If your device blocks that, the system **Set as** sheet opens so you can pick Lock screen. **Try live video on lock screen** opens the live wallpaper preview instead.

To change the video, crop or range later, edit it in the app and apply the wallpaper again.

## Known limitations

- Live lock screen support depends on your EMUI or HarmonyOS version. Huawei's Themes app may override third-party live wallpapers on the lock screen. Separate home and lock videos need Android 14 level support (HarmonyOS 4.2 and later); on older systems the lock screen uses the home screen settings.
- HarmonyOS NEXT (5.0 and later) does not run Android APKs and is not supported.
- Looping uses `MediaPlayer` seeking, so there may be a brief hitch at the loop point, especially with long keyframe intervals. Re-encoding the clip with frequent keyframes helps.
- Very high-resolution or long videos use more battery and storage. The selected video is copied into the app's private storage.
- HTML-based animated backgrounds are not supported yet.
- The APKs in `apks/` may be older than the current source code. Build from source for the latest changes.

## Project structure

```
app/src/main/java/com/me/fluxbg/
  MainActivity.kt           Home screen: library of stored videos, add and delete
  EditorActivity.kt         Editor: preview playback, crop, timeline trim, apply actions
  Ui.kt                     Shared colors and view helpers
  Thumbs.kt                 Cached poster frames and durations for the library
  PreviewFrame.kt           Keeps the video and crop overlay on the same rectangle
  CropOverlayView.kt        Drag and pinch crop window
  TrimBarView.kt            Timeline with thumbnails, trim handles and playhead
  FluxWallpaperService.kt   Live wallpaper service (home / lock aware)
  VideoRenderer.kt          OpenGL ES renderer for the cropped, trimmed video
  Store.kt                  Per-screen settings storage and crop fitting
apks/                       Prebuilt APKs (may not match the latest code)
```

---

## 📜 License

This application's source code is shared under the **PolyForm Noncommercial License 1.0.0**. 

* **Personal & Educational Use:** Free to use, modify, and explore. You must give credit to the original author.
* **Commercial Use:** If you intend to use this code to earn revenue, build a commercial product, or use it within a business, you **must purchase a commercial license**.

For commercial licensing terms and pricing, please contact me at: `kurtislam100@gmail.com`

---

## 🙌 Acknowledgements

Built with ❤️ by Kurtis.