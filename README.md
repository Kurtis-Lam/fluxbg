# Video Wall

A minimal Android live wallpaper app that plays a video from your gallery as the home screen background, with an optional loop range so only a chosen part of the clip repeats.

Built and tested for the Huawei Pura 70 Ultra (HarmonyOS 4.2, which runs Android APKs), but it should work on any Android 8.0+ (API 26) device.

## Features

- Pick any video from the gallery
- Set a loop start and end time in seconds (end = 0 loops the whole video)
- Muted playback, scaled to fill the screen
- Pauses when the screen is off to save battery

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

1. Install the APK and open **Video Wall**.
2. Tap **Choose video from gallery**.
3. Enter the loop start and end in seconds.
4. Tap **Save loop range**, then **Set as wallpaper**, and confirm in the system preview.

To change the video or range later, update it in the app and apply the wallpaper again.

## Known limitations

- Android live wallpapers apply to the home screen. Lock screen support depends on the device and system version. On Huawei devices, the built-in Themes app may override it.
- HarmonyOS versions that drop Android app support cannot install this APK.
- Looping uses `MediaPlayer` seeking, so there may be a brief hitch at the loop point, especially with long keyframe intervals. Re-encoding the clip with frequent keyframes helps.
- HTML-based animated backgrounds are not supported yet.

## Project structure

```
app/src/main/java/com/me/vidwall/
  MainActivity.kt          Video picker, loop range, wallpaper launcher
  VideoWallpaperService.kt Live wallpaper engine (MediaPlayer + loop logic)
```

## License

MIT. Add a `LICENSE` file if you want to publish under it.