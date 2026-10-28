# LocationLogger

A small Android app that records the **places you visit**. At a configurable interval
(default every 5 minutes) it gets the phone's location and reverse-geocodes it to an address
(optionally merged into a place name, see [Places](#places)). From that it builds a list of
**visits** shown in the app's **Visits** tab, newest first:

```
Today · Wed 28 Oct 2026
● Home
  arrived 17:40 – now
  1 h 5 min so far · travelled 12.3 km from previous stop
  Storgata 1, 0155 Oslo
  arrived 15:10 – left 17:20
  2 h 10 min · travelled 850 m from previous stop
```

The address is looked up in the phone's language: "street + house number, postcode + city",
falling back to the geocoder's full address line when there is no street name.

- Runs as a **foreground service** (`foregroundServiceType="location"`) with a persistent notification,
  so it keeps working with the screen off and the app in the background.
- **Restarts after reboot** (and after an app update) if it was running.
- No Google Play Services dependency: plain `LocationManager` + `android.location.Geocoder`
  (async Geocoder API on Android 13+).
- Kotlin, framework APIs only (no AndroidX). minSdk 26 (Android 8.0), targetSdk 34.

## Where the data is saved

Visits are stored privately in the app (`files/visits.json` in the app's data directory; not visible
in a file manager, removed on uninstall). Nothing is written to shared storage.

## Screens

The app has two tabs:

- **Visits** – running/stopped status (with a warning if a permission or battery setting needs
  attention), the last check, **Check now** (its progress, "Checking now …", and then its result are shown in the
  status line for 2 minutes), the "Seen once" candidate and the visit list. The
  current visit's duration updates every minute.
- **Settings** – detailed status (GPS control, location permission, battery optimization),
  **Start / Stop logging** and the settings below. Back on the Settings tab returns to Visits; the
  app reopens on the tab you used last.

## Settings

- **Start logging / Stop logging** – start or stop the background service.
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
- **Places (merge addresses)** – an editable list that merges several geocoder addresses for the
  same spot into one name. See [Places](#places).
- **Allow location "all the time"** and **Turn off battery optimization** – shortcuts to the
  permissions needed for reliable background logging.

## Places

The geocoder often returns slightly different addresses for the same position. Tap
**Places (merge addresses) …** on the Settings tab and enter one place per line:

```
Name;address 1;address 2;...
Home;Example Street 1, 0000 Town;Other Road 2, 0000 Town
```

When a looked-up address matches any address on a line (case, surrounding spaces and repeated
spaces are ignored), the **name** (the first part) is logged instead. Empty lines and lines starting
with `#` are ignored. The dialog shows the last address the phone returned, and *Insert last address*
pastes it at the cursor. The list is stored on the device only (app preferences), never in this
repository.

## Visits

For each successful check the address is first corrected with the place list, then:

1. Same place as the current visit: the visit's **departure** moves to this check.
2. A new place: remembered as a candidate (first sighting), not a visit yet.
3. The next successful check gives the same candidate: a new visit starts. Its **arrival** is the
   candidate's first sighting; the previous visit keeps as departure its last check at that place.

So a place must be seen on two checks in a row to become a visit (a single check elsewhere, e.g.
A → B → A, does not split the stay at A). Checks without a location fix or address are skipped for
this. The current visit shows "now" and its running duration; when logging is stopped it shows
"last seen". Visits without a recorded departure show "left ?" and "up to …" (time until the next visit).
Each visit's coordinates are the average of its first and confirming fix.

### Travelled distance

The distance shown for a visit is the **travelled distance** from leaving the previous stop to
arriving at this one: the sum of the straight legs between consecutive fixes, from the last fix
at the previous stop, through every check while moving (unconfirmed candidates and fixes without an
address included), to the first sighting of the new stop.

- Fixes with an accuracy worse than 100 m are ignored.
- Fixes at a stop never add distance: each check still at the current stop restarts the count
  (so GPS jitter, and a short detour that returns to the same stop, are not counted), and the
  confirming fix at the new stop is not counted.
- It is an approximation: it only knows where the phone was at each check, so with 5-minute checks
  winding roads are cut short (and a trip shorter than one interval is roughly a straight line).
  A shorter interval gives a closer result at the cost of more GPS use and battery.

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
reinstall). If the Settings tab shows **"Missing permission to control GPS
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
      LogStore.kt          preferences
      VisitStore.kt        visits (visits.json), arrival/departure, travelled distance
      Places.kt            place list: merges several addresses into one name
      BootReceiver.kt      restart after reboot / update
    src/main/res/          layout, icons, strings
```

The package name `no.grokbot.stedslogger` is kept from the app's original (Norwegian) name, *Stedslogger*.

## License

Released under the [MIT License](LICENSE).
