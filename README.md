# fluxbg

An Android wallpaper app designed for Huawei phones. Add a static image or a dynamic video from your gallery, crop it, trim videos on a video-editor style timeline, and use it as your home screen and/or lock screen wallpaper.

Designed for Huawei phones (EMUI 8+ and HarmonyOS versions that run Android APKs, such as HarmonyOS 2 to 4.x). It targets Android 8.0+ (API 26) and works on all Huawei phone lines: Pura, Mate, nova, P, Y and the foldables. It should also work on other Android devices.

## Features

- Home screen is **My library**, with **Images** and **Videos** buttons to switch between everything you have added
- **Add wallpaper** asks for **Static** (upload an image) or **Dynamic** (pick a video), then goes straight to the editor
- Crop with a fixed-aspect window matched to your screen: drag the sides or corners to resize, drag inside to move, or pinch
- Trim videos with a timeline bar: thumbnail strip, draggable in/out handles and a scrub playhead; long videos show 10 seconds at a time and the timeline scrolls sideways
- Preview playback that loops only the trimmed range
- Separate settings for the home screen, the lock screen, or both
- Applying always asks before overwriting your current wallpaper, then returns you to the library
- Static images are set directly, with no system preview
- Lock screen option that sets the frame at the playhead (or the cropped image), checks that the phone really changed it, and falls back to the system Set as sheet
- Asks for storage access when the fallback needs it (Android 9 and older)
- App-styled pop-ups for every confirmation and message
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
2. Tap **Add wallpaper** and choose **Static** to upload an image or **Dynamic** to pick a video. Or open **My library**, use **Images** / **Videos** to switch, and tap an item you added earlier. Hold an item to delete it.
3. In the editor, drag the sides or corners of the crop window (or pinch) to frame the shot, and drag inside it to move it.
4. For videos, drag the handles on the timeline to set the in and out points, tap the bar to seek, and swipe the timeline to scroll through long clips. Tap the play button to preview the loop.
5. Under **Apply to**, choose **Home screen**, **Lock screen** or **Both**, then tap the main button. fluxbg warns that it will overwrite your current wallpaper; confirm to apply and return to the library.
6. Images are applied immediately. Dynamic videos open the system live wallpaper preview; confirm it there.
7. For the lock screen, the main button sets a still picture (the frame at the playhead for videos). If your phone does not confirm the change, fluxbg offers the system **Set as** sheet so you can pick Lock screen. **Try live video on lock screen** opens the live wallpaper preview instead.

To change the file, crop or range later, open it from the library and apply it again.

## Known limitations

- Live lock screen support depends on your EMUI or HarmonyOS version. Huawei's Themes app may override third-party live wallpapers on the lock screen. Separate home and lock videos need Android 14 level support (HarmonyOS 4.2 and later); on older systems the lock screen uses the home screen settings.
- HarmonyOS NEXT (5.0 and later) does not run Android APKs and is not supported.
- Looping uses `MediaPlayer` seeking, so there may be a brief hitch at the loop point, especially with long keyframe intervals. Re-encoding the clip with frequent keyframes helps.
- Very high-resolution or long videos use more battery and storage. Selected videos and images are copied into the app's private storage.
- HTML-based animated backgrounds are not supported yet.
- The APKs in `apks/` may be older than the current source code. Build from source for the latest changes.

## Project structure

```
app/src/main/java/com/me/fluxbg/
  MainActivity.kt           Home screen: My library (Images / Videos), add, delete
  EditorActivity.kt         Editor: preview, crop, timeline trim, apply actions
  FluxDialog.kt             App-styled pop-ups (confirm, info, option sheet)
  Images.kt                 Image decoding, EXIF rotation and cropping
  Ui.kt                     Shared colors and view helpers
  Thumbs.kt                 Cached previews and durations for the library
  PreviewFrame.kt           Keeps the preview and crop overlay on the same rectangle
  CropOverlayView.kt        Crop window: drag sides and corners, move, pinch
  TrimBarView.kt            Scrollable timeline with thumbnails, trim handles and playhead
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