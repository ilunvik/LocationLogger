# LocationLogger

A small Android app that logs the **street address** you are at. At a configurable interval
(default every 5 minutes) it gets the phone's location, reverse-geocodes it to an address and,
**only if the address has changed**, appends a line to `location.txt`:

```
2026-10-26 03:49 | Storgata 1, 0155 Oslo
```

The timestamp uses the phone's local time, and the address is looked up in the phone's language.
The address is "street + house number, postcode + city",
falling back to the geocoder's full address line when there is no street name.

- Runs as a **foreground service** (`foregroundServiceType="location"`) with a persistent notification,
  so it keeps working with the screen off and the app in the background.
- **Restarts after reboot** (and after an app update) if it was running.
- No Google Play Services dependency: plain `LocationManager` + `android.location.Geocoder`
  (async Geocoder API on Android 13+).
- Kotlin, framework APIs only (no AndroidX). minSdk 26 (Android 8.0), targetSdk 34.

## Where the log is saved

| Android version | Location |
|---|---|
| 10 and newer | `Internal storage/Documents/location.txt` (via MediaStore, visible in any file manager) |
| 8 – 9, or if MediaStore fails | `Android/data/no.grokbot.stedslogger/files/location.txt` |

The latest entries (newest first) are also shown on the main screen.

## Settings (main screen)

- **Start / Stop / Check now** – start or stop logging, or run a check immediately.
- **Check every (minutes)** – interval between checks, 1–1440 minutes, default 5. Use −/+ or type a
  value and tap *Apply*. Takes effect immediately in the running service (the next check is
  rescheduled relative to the last one).
- **Turn GPS on when needed before locating** (default on) – turns GPS on if it's off, and turns it
  off again afterwards only if it was off. For each check:
  1. Read whether system location is currently on (`LocationManager.isLocationEnabled()`).
  2. If it is off, switch it on (`Settings.Secure.LOCATION_MODE = 3`, with fallbacks) and wait up to 6 s.
  3. Get a single location fix (GPS first, then network; 60 s timeout each), then reverse-geocode and log.
  4. Right after the fix completes or times out, switch location off again **only if it was off before
     the check**. If you had it on, it is left on.

  This needs the `WRITE_SECURE_SETTINGS` permission (see below). Without it, the app just requests a
  fix without toggling and shows a warning in the status.
- **Allow location "all the time"** and **Turn off battery optimization** – shortcuts to the
  permissions needed for reliable background logging.

## Permissions

On first start the app asks for:

1. **Location** (precise) and, on Android 13+, **notifications**.
2. **Background location** – choose **"Allow all the time"** on the settings screen (Android 10+).
   Without it Android may stop logging in the background and after a reboot.
3. Optionally, **ignore battery optimizations** – strongly recommended, especially on
   Samsung/Xiaomi/Huawei/OnePlus devices.

### WRITE_SECURE_SETTINGS (for the GPS on/off feature)

This is a privileged permission an app cannot request itself. Grant it once over adb:

```sh
adb shell pm grant no.grokbot.stedslogger android.permission.WRITE_SECURE_SETTINGS
```

It normally survives app updates, but is lost on uninstall (and some devices drop it on a
reinstall). If the main screen shows **"Missing permission to control GPS
(grant WRITE_SECURE_SETTINGS via adb)"**, just run the command above again. Check that it took with:

```sh
adb shell dumpsys package no.grokbot.stedslogger | grep WRITE_SECURE_SETTINGS
# expect: WRITE_SECURE_SETTINGS: granted=true
```

## Building

Requirements: JDK 17 and the Android SDK (platform `android-35`, build-tools 35.0.0).

```sh
cd src
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or set ANDROID_HOME
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleRelease
```

The APK is written to `src/app/build/outputs/apk/release/app-release.apk`.
The release build is signed with the local Android **debug** key (`~/.android/debug.keystore`, created
automatically by the Android tooling) so it installs directly; for distribution, configure your own
signing key in `src/app/build.gradle.kts`. Note that an APK signed with a different key cannot be
installed as an update over one signed with another key.

## Installing

Copy the APK to the phone and open it (allow "Install unknown apps" for your file manager or browser),
or use adb:

```sh
adb install -r app-release.apk
```

## Caveats

- Reverse geocoding needs an internet connection and a geocoder backend on the phone (normally provided
  by Google services). If no address is found, that check is skipped.
- The service holds a partial wake lock (CPU only) so the timer keeps running in Doze; GPS itself is only
  used during each check. Short intervals use more battery.
- Turning location on/off by writing `LOCATION_MODE` works on most Android versions but is not
  guaranteed on every vendor build; if Android ignores the change the app shows a warning and tries a fix
  anyway.
- If you switch GPS on yourself during the few seconds a check is running (while the app has it on),
  it will still be switched off after that check.
- "Same address" is an exact text comparison; GPS jitter between neighbouring buildings can produce
  extra entries.

## Project layout

```
README.md
LICENSE
.gitignore
src/                      Gradle project
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradlew, gradlew.bat, gradle/wrapper/
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/no/grokbot/stedslogger/
      MainActivity.kt      UI, permissions, settings
      LocationService.kt   foreground service, checks, GPS toggling, logging
      GpsToggle.kt         system location on/off via Settings.Secure
      LogStore.kt          location.txt (MediaStore / fallback) and preferences
      BootReceiver.kt      restart after reboot / update
    src/main/res/          layout, icons, strings
```

The package name `no.grokbot.stedslogger` is kept from the app's original (Norwegian) name, *Stedslogger*.

## License

Released under the [MIT License](LICENSE).
