# LocationLogger

A small Android app that records the **places you visit**. At a configurable interval
(default every 5 minutes) it gets the phone's location and reverse-geocodes it to an address
(optionally merged into a place name, see [Places](#places)). From that it builds a list of
**visits** shown in the app's **Visits** tab, newest first:

```
Today · Wed 28 Oct 2026
● Home
  17:40 – now
  1 h 5 min so far
     🚗 Driving · 20 min · 12.3 km
  Storgata 1, 0155 Oslo
  15:10 – 17:20
  2 h 10 min
     🚗 Driving · 5 min · 850 m
```

Between two stops a lighter, indented **Driving** row shows the travel time (the next stop's
arrival − the previous stop's departure) and the travelled distance; an unknown value is shown as
"–", and the row is left out if both are unknown. It sits under the stop it leads to. While you are
on the move (the current stop was left and a new place has been seen once but not confirmed),
a "🚗 Driving now · … so far · …" row at the top shows the time since the last check at the stop and
the distance so far.

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
in a file manager, removed on uninstall). Since 1.25 a debug log is kept next to it (`files/events.log`,
at most 4000 entries, oldest dropped first). Nothing is written to shared storage.

## Screens

The app has three tabs:

- **Visits** – running/stopped status (with a warning if a permission or battery setting needs
  attention), the last check, **Check now** (its progress, "Checking now …", and then its result are shown in the
  status line for 2 minutes), the "Seen once" candidate and the visit list. The
  current visit's duration updates every minute.
- **Settings** – detailed status (GPS control, location permission, battery optimization),
  **Start / Stop logging** and the settings below.
- **Log** (since 1.25, labels in Norwegian) – every debug entry, newest first, with time (phone's time
  zone), type and details: check started / result (`SJEKK`), every raw location fix with coordinates,
  provider, accuracy and age (`POSISJON`), visit outcome (`BESØK`), the stored visits' arrival / last seen
  (`BESØKSLISTE`), dashboard reports and wireless-debugging changes (`ADB`), Shizuku running/stopped (`SHIZUKU`, 1.27+), network changes (`NETT`),
  service start/stop, boot, update and alarm restarts (`TJENESTE`) and errors (`FEIL`). A type filter,
  **Oppdater** (refresh), **Kopier** (copy as text), **Del** (share) and **Tøm** (clear, after confirming;
  visits are kept). Long-press an entry to copy it.

Back on the Settings or Log tab returns to Visits; the app reopens on the tab you used last.

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
- **Places (merge addresses)** – an editable list, edited directly on the Settings tab, that merges several geocoder addresses for the
  same spot into one name. See [Places](#places).
- **Allow location "all the time"** and **Turn off battery optimization** – shortcuts to the
  permissions needed for reliable background logging.

## Places

The geocoder often returns slightly different addresses for the same position. In the
**Places (merge addresses)** section at the bottom of the Settings tab, enter one place per line:

```
Name;address 1;address 2;...
Home;Example Street 1, 0000 Town;Other Road 2, 0000 Town
```

When a looked-up address matches any address on a line, the **name** (the first part) is shown
instead. Matching ignores case, punctuation, extra or no-break spaces and invisible characters; an
address also matches the same street (the part before the first comma) when the postcode/town is
missing on one side or the postcodes agree. The reversed order `address;Name` is accepted when the
first part looks like an address (has a digit) and the last does not. The list is applied when the
visits are **shown**, so stops logged before a place was added or renamed show the new name as soon
as the list is saved. Empty lines and lines starting
with `#` are ignored. The section shows the last address the phone returned (selectable), *Insert last address*
pastes it at the cursor, and *Save* stores the list ("Saved (N places)" / "Not saved" is shown next
to it). Leaving the Settings tab with unsaved changes asks whether to save or discard them. The list is stored on the device only (app preferences), never in this
repository.

## Dashboard URL (optional)

Settings › **Dashboard URL**: if set, the app reports the phone's wireless-debugging address to that URL,
so the current `adb connect` port is always known. On each check and on network changes it reads
`adb_wifi_enabled` (read-only) and looks up the phone's own `_adb-tls-connect._tcp` service via NSD
(mDNS), matching the service's host IP against the phone's own interface IPs. `tailscaleIp` is only taken from the VPN interface
(Tailscale's `tun*`), never from mobile data, where carriers also use 100.64.0.0/10 (CGNAT) addresses. It POSTs JSON

```
{"ip": "100.x.y.z", "tailscaleIp": "100.x.y.z", "lanIp": "192.168.x.y", "port": 40000,
 "adbWifi": "on", "ts": 1700000000000, "device": "<device name>",
 "deviceId": "<random UUID made by the app>", "model": "<Build.MODEL>"}
```

(plus `"tcp5555": true/false` – whether adbd listens on TCP 5555, i.e. `adb tcpip 5555` mode, read from
`/proc/net/tcp*` or a short local connection attempt). It reports right away (debounced ~4 s) on every
network change (Wi-Fi/mobile switch, network lost/available, new IP), after boot and app updates, when the
logging service starts, and right after it has switched wireless debugging back on (once the new port is
found via NSD); otherwise only when the value changed, or about once an hour as a heartbeat. The dashboard
host can react to each report (e.g. switch adbd to TCP 5555) instead of polling (`adbWifi: "off"` and `port: null`
when wireless debugging is off). Settings shows "Last reported: ip:port at HH:MM". If the dashboard's host name cannot be resolved
(DNS failure, e.g. right after a network change or while the VPN is down), the app (1.26+) retries after
5 s, 15 s and 45 s, and on each attempt also tries a fallback IP: **Dashbord-IP (reserve)** in Settings
(optional, an IP literal), then the IP the host last resolved to after a successful report (learned
automatically). Over HTTPS the fallback connects to the IP but keeps TLS SNI, the certificate check and the
host name check for the original host, so the connection is still verified. Every attempt and the path
that worked is shown in the Log tab (`ADB`). **Keep wireless debugging on** (default on): if
the app has `WRITE_SECURE_SETTINGS` (granted via `adb shell pm grant no.grokbot.stedslogger
android.permission.WRITE_SECURE_SETTINGS`) and wireless debugging is off while the phone is on Wi-Fi,
it sets `Settings.Global adb_wifi_enabled = 1` – the only setting it writes for this. Nothing else is
turned on or off. Empty URL = disabled. No URL is built in; the endpoint should only be
reachable from your own network (e.g. a tailnet).

**Shizuku watch (1.27+).** At every periodic check (also in Doze, via the wake-up alarm) the app checks
whether [Shizuku](https://shizuku.rikka.app/) is running, and each report carries
`"shizuku": "running" | "stopped" | "not_installed" | "unknown"`. Detection uses the Shizuku API library
(`dev.rikka.shizuku:api` + `provider`): the Shizuku server hands its binder to apps that declare
`rikka.shizuku.ShizukuProvider`, and `Shizuku.pingBinder()` tells whether it is alive. The app never asks
for Shizuku permission and never calls anything on the binder besides ping (it does appear in Shizuku's app
list as a client that has not been granted access). A binder-dead listener notices a stop at once. When
Shizuku has stopped, a report is sent right away and then at most every 2 minutes (in practice at each
check) while it stays stopped, so the dashboard host can start it again over adb; when it is back, one
more report is sent. State changes are logged in the Log tab under `SHIZUKU` ("Shizuku stoppet – varsler
Asus" / "Shizuku kjører igjen"), and Settings shows the current Shizuku state under the dashboard status.

## Street View

Optionally, each stop with coordinates shows a square Street View image (96 dp, rounded) to the right of
its text; stops without an image keep their text full width.
Tapping the image opens the spot in Google Maps as a normal map with a pin
(`https://www.google.com/maps/search/?api=1&query=lat,lon`; the Maps app if installed, otherwise
the browser). Imported stops without coordinates get no image.

This needs your own Google API key, entered in the **Street View** section of the Settings tab
(stored only on the device, in the app's preferences; never logged). Without a key the app shows no
images and makes no network calls for them. To create a key:

1. Google Cloud Console → create a project (billing must be enabled).
2. Enable **Street View Static API**.
3. Credentials → **Create API key**.
4. Restrict it to **Android apps**: package `no.grokbot.stedslogger` and the SHA-1 of the
   certificate your APK is signed with (the Settings tab shows it).
5. Restrict the API to **Street View Static API**.

For each stop the app first asks the free metadata endpoint whether there is an outdoor panorama
within 50 m; only if the status is `OK` is the square 640×640 image downloaded (older cached images are
center-cropped by the view). Requests carry the
`X-Android-Package` and `X-Android-Cert` headers so an Android-restricted key works.
### Image per place

If a stop's name is a place in the [Places](#places) list, the image belongs to that **place**:
the first time, Street View from that stop is fetched and saved as the place's image
(`files/placeimages/`, app-private, never in this repository), and all stops of the place show it.
An unnamed stop uses its own spot (coordinates rounded to 5 decimals). Renaming a place gives it a
new image. Long-press the image (or the stop row, e.g. after removing the image) for:

- **Choose photo…** – pick a photo on the phone; it is center-cropped to a square, scaled to 640×640 and saved.
- **Other Street View angle** – fetch Street View again, turned 90° further each time.
- **Use Street View from this stop** – fetch Street View from this stop's coordinates.
- **Remove image** – delete it and keep it hidden (no automatic refetch) until one of the options
  above is used.

Places with a saved image are shown with a small square thumbnail under the place list on the Settings tab.
Chosen photos are shown even without an API key.

Downloads are cached in the app's private files (`files/streetview/`) by coordinates rounded to
5 decimals: the image, or a marker when Google has no panorama there, so each spot is fetched only
once. Other errors (invalid key, quota, network) are not cached on disk and are retried after the
app restarts or the key is saved again.

## Visits

For each successful check the address is first corrected with the place list, then:

1. Same place as the current visit: the visit's **departure** moves to this check.
2. A new place: remembered as a candidate (first sighting), not a visit yet.
3. The next successful check gives the same candidate: a new visit starts. Its **arrival** is the
   candidate's first sighting; the previous visit keeps as departure its last check at that place.

So a place must be seen on two checks in a row to become a visit (a single check elsewhere, e.g.
A → B → A, does not split the stay at A). Checks without a location fix or address are skipped for
this. The current visit shows "now" and its running duration; when logging is stopped it shows
"last seen". Visits without a recorded departure show "– ?" and "up to …" (time until the next visit).
Each visit's coordinates are the average of its first and confirming fix.

### Travelled distance

The distance shown on a Driving row is the **travelled distance** from leaving the previous stop to
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
- Each check is triggered by an AlarmManager alarm (`setExactAndAllowWhileIdle`, RTC_WAKEUP) that wakes
  the phone in Doze / deep sleep with the screen off; a Handler timer is kept as a backup, and both use
  the same wall-clock "due" test so a check never runs twice. Exact alarms use `USE_EXACT_ALARM`
  (granted at install on Android 13+; `SCHEDULE_EXACT_ALARM` on 12); if exact alarms are not allowed
  the app falls back to `setAndAllowWhileIdle` (Settings shows "Wake-up alarms: exact/inexact"). The
  alarm is cancelled when logging stops and set again when the service restarts (boot, update). Some
  phones ignore an app's wake lock (Samsung), which is why a Handler timer alone stalled in deep sleep;
  a short timed wake lock is still held during each check. GPS itself is only used during each check.
- Each check asks GPS, network and (Android 12+) the fused provider at the same time; the first fix
  with an accuracy of 100 m or better wins, otherwise the most accurate one within 60 s. Without a
  new fix, a recent last-known or passively received fix (not older than the interval, at least 10
  min) is used and marked "last known fix" in *Last check*. Otherwise *Last check* shows why, per
  provider (no fix / no answer in 60 s / permission denied), and hints when location is allowed only
  while the app is in use. Short intervals use more battery.
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
      EventLog.kt          debug log (events.log, capped)
      LogTab.kt            the Log tab
      AdbReporter.kt       optional dashboard report of the wireless-debugging address
      CheckAlarm.kt        wake-up alarms for the periodic checks
      VisitStore.kt        visits (visits.json), arrival/departure, travelled distance
      Places.kt            place list: merges several addresses into one name
      StreetView.kt        Street View images for stops (API key, download, cache)
      BootReceiver.kt      restart after reboot / update
    src/main/res/          layout, icons, strings
```

The package name `no.grokbot.stedslogger` is kept from the app's original (Norwegian) name, *Stedslogger*.

## License

Released under the [MIT License](LICENSE).
