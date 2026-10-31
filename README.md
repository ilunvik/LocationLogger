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
  On Android 14+ a `location` foreground service may only be started while the app is in the
  foreground; when the service is (re)started from the background (wake-up alarm, boot, app update)
  it falls back to type `specialUse` (1.28.1+) and still gets locations through the background
  location permission ("Allow all the time"). If going foreground fails anyway, the wake-up alarm is
  kept so the next alarm retries instead of logging stopping for good.
- **Restarts after reboot** (and after an app update) if it was running.
- Uses Google Play services' fused location when available (1.28+), otherwise plain
  `LocationManager`; reverse geocoding with `android.location.Geocoder` (async API on Android 13+).
- Kotlin, framework APIs only (no AndroidX). minSdk 26 (Android 8.0), targetSdk 34.

## Where the data is saved

Visits are stored privately in the app (`files/visits.json` in the app's data directory; not visible
in a file manager, removed on uninstall). Since 1.25 a debug log is kept next to it (`files/events.log`,
at most 4000 entries, oldest dropped first). Nothing is written to shared storage.

## Screens

The app has four tabs (the **Samtaler** tab since 1.32, see [Calls and SMS](#calls-and-sms-132)):

- **Visits** – running/stopped status (with a warning if a permission or battery setting needs
  attention), the last check, **Check now** (its progress, "Checking now …", and then its result are shown in the
  status line for 2 minutes), the "Seen once" candidate and the visit list. The
  current visit's duration updates every minute.
- **Settings** – detailed status (GPS control, location permission, battery optimization),
  **Start / Stop logging** and the settings below.
- **Log** (since 1.25, labels in Norwegian) – every debug entry, newest first, with time (phone's time
  zone), type and details: check started / result (`SJEKK`), every raw location fix with coordinates,
  provider, accuracy and age (`POSISJON`), visit outcome (`BESØK`), the stored visits' arrival / last seen
  (`BESØKSLISTE`), dashboard reports and wireless-debugging changes (`ADB`), Shizuku running/stopped (`SHIZUKU`, 1.27+), Tailscale up/off and start attempts (`TAILSCALE`, 1.29+), network changes (`NETT`),
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
  3. Get a location with the tiered strategy below (1.28+), then reverse-geocode and log.
  4. When the check is done (or has timed out), switch location off again **only if it was off before
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

**Tailscale watch (1.29+).** At every periodic check, at service start and on network changes (no extra
polling or wake locks) the app checks whether the [Tailscale](https://tailscale.com/) VPN is up: a VPN
interface (`TRANSPORT_VPN` or `tun*`) with an address in 100.64.0.0/10. Each report carries
`"tailscale": "running" | "stopped" | "not_installed" | "unknown"` (unknown during the first 90 s after boot
and 30 s after the app starts). When it is off and "Keep Tailscale on" is checked (default), the app sends
Tailscale's documented intent – an explicit broadcast `com.tailscale.ipn.CONNECT_VPN` to
`com.tailscale.ipn/.IPNReceiver` – at most every 3 minutes, and every 15 minutes after two failed attempts.
Tailscale starts the VPN without UI when its VPN permission was granted before; otherwise it shows its own
notification. After two failed attempts LocationLogger shows a notification "Tailscale er av" (tap opens
Tailscale). When Tailscale is back, a report is sent at once. Log category `TAILSCALE`; Settings shows the state.

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

## Calls and SMS (1.32)

Everything here is **off or read-only by default**, every permission is asked for in the app, and the
call log, SMS, MMS and recordings are **only read on the phone** – they are never stored by the app,
copied or sent anywhere (not to the dashboard either). The only thing that leaves the phone is an unknown
number sent to the directory lookup.

- **Samtaler tab** – incoming / outgoing / missed / rejected calls (`CallLog`), SMS (`Telephony.Sms`) and
  MMS (`Telephony.Mms`, text, attachments and sender) newest first (up to 400 calls, 400 SMS, 150 MMS), with the
  contact name, the looked-up name ("Name (1881.no)") or the number, time, call duration or the start of
  the text, and direction icons (↙ in, ↗ out, ✕ missed). Filter chips **Alle / Samtaler / SMS/MMS**.
  Tap an entry for details (full SMS text).
- **Number lookup** – Norwegian 8-digit numbers that are not in the contacts are looked up, in this
  order (each source can be switched off in Settings): **1881.no** (`/?query=<number>`; the result box
  whose `tel:` link is exactly the number, or the detail page a single hit redirects to), **gulesider.no
  personer** (`/<number>/personer`) and **gulesider.no bedrifter** (`/<number>/bedrifter`). Since 1.34
  gulesider.no is read in an **off-screen WebView** (the phone's real browser engine with Chrome's user
  agent, JavaScript, DOM storage and persistent cookies), so its Cloudflare check runs as in a normal
  browser; the page is polled until the check is over (max 20 s) and `assets/lookup_extract.js` reads the
  entry whose number matches (JSON-LD, 1881 result boxes / detail page, result cards). 1881.no is fetched
  with a plain request first and falls back to the WebView if that is blocked. Results are cached on the
  phone (`files/lookup_cache.json`; found 90 days, not found 14 days). At least 3 s between lookups, at
  most 40 per hour; an HTTP block pauses that site for 6 h, a check the WebView could not finish for 1 h.
  New entries in the tab are looked up automatically (max 15 per refresh), or with **🔍 Slå opp** on an
  entry, which also offers the **web view**.
- **Web view (1.34)** – **🌐 Nettvisning** / *Åpne … i nettvisning* shows the 1881 or gulesider page for
  the number inside the app, so a Cloudflare check can be completed by hand (the cookies are shared with
  the background lookup, which lifts the pause). **Bruk dette navnet** reads the matching name from the
  page (or lets you type one) and saves it as the lookup result, optionally adding it to the contacts.
- **Oppslag tab (1.36)** – the web view is a permanent tab. Type or paste a number (or switch the field to
  *abc* and type a name) and pick **1881**, **Gulesider personer** or **Gulesider bedrifter**; a back /
  forward / reload / address bar sits above the page (only 1881.no and gulesider.no are opened). **Bruk
  dette navnet** and **Lagre + kontakter** are enabled while the page is about a number. The page is kept
  when switching tabs and when the Fold is folded or unfolded (MainActivity handles those configuration
  changes itself); Android back steps through the page history. **🌐 Nettvisning** on a call row switches
  to this tab with the number filled in and loaded.
- **Add to contacts** – **+ Legg til i kontakter** on an entry with a looked-up name creates a new phone
  contact (name, number, note "Oppslag via LocationLogger (source, date)"). With *Legg automatisk til
  oppslag i kontakter* (off by default) this happens after every successful lookup. An existing contact
  is never changed or overwritten. **Kontaktlager** (1.35, button under that setting) picks where new contacts are
  saved: any account whose contacts sync adapter supports uploading (Google, Samsung account, Exchange …;
  chat apps and SIM cards are left out) or *Telefon (lokal)* (Samsung's phone storage, else no account).
  Default: the first Google account, else the phone. If the chosen account disappears, the default is
  used and the Log says so; if an account refuses the insert, the contact is saved on the phone. Accounts
  are visible to the app through the contacts permission (no extra permission on Android 8+).
- **Call recordings** – ordinary apps cannot record the other party of a call on modern Android
  (`VOICE_CALL` is reserved for system apps), so the app plays recordings saved by other recorders,
  found through MediaStore (`READ_MEDIA_AUDIO`, or `READ_EXTERNAL_STORAGE` on Android ≤ 12):
  Samsung's built-in call recording (`Recordings/Call/` or `Call/`, `…_YYMMDD_HHMMSS.m4a`) and ACR Phone
  (`Recordings/ACRPhone/…/<number>-<dir>-<epoch ms>.m4a`). A recording is shown as **▶ Opptak** on the
  call it belongs to (start within 60 s before the call to 90 s after it ended, same number when the file
  name has one) and plays in an in-app player (play/pause, seek bar). Files are only read.
- **Experimental recorder** (off by default, *tar trolig bare opp din egen stemme*): records the
  microphone (`VOICE_COMMUNICATION`, else `MIC`) in a `microphone` foreground service while a call is
  active, into the app-specific folder `Android/data/no.grokbot.stedslogger/files/Samtaleopptak/`.
  Android 14+ may refuse a microphone service started from the background, or record silence.
- **Popup** (*Popup ved innkommende samtale/SMS/MMS*, off): a high-priority (heads-up) notification with the
  caller's / sender's name or lookup result; updated when the lookup finishes; a missed call from a
  looked-up number leaves a normal notification. No overlay permission is needed.
- **Read aloud** (*Les opp innringer/SMS/MMS*, off): Android text-to-speech in Norwegian (nb-NO):
  "Anrop fra …", "SMS fra …" and, with *Les også opp SMS- og MMS-teksten*, the text. Needs a Norwegian voice in
  the phone's text-to-speech settings.
- **MMS attachments (1.33)** – MMS parts (`content://mms/part`, `mid=<id>`) are read on the phone:
  text parts (the `text` column, or the part stream when stored as a file), the subject (UTF-8
  re-decoded when needed) and attachments. Images are shown as thumbnails in the list and full size in
  the details; tap one for a fullscreen view. Video, audio, vCard and other parts are chips that open
  with `ACTION_VIEW` in an app of your choice: straight from `content://mms/part/<id>` with a read
  grant when the MMS store allows it, otherwise through a temporary copy in the app's cache (served by
  the app's own non-exported provider, deleted after a day). The sender (address type 137) and, for
  group MMS, the other participants (151 To / 130 Cc / 129 Bcc) are shown. Delivery / read reports are
  hidden. Chat (RCS) messages that Samsung Messages stores as MMS show the same way.
- **New MMS: popup and read aloud (1.33)** – MMS never trigger `SMS_RECEIVED`, and `WAP_PUSH_DELIVER`
  only reaches the default SMS app, so the app watches the MMS store (`ContentObserver` on
  `content://mms` and `content://mms-sms/`, registered by the logging service and the app). A new inbox
  MMS is announced once its parts are downloaded (re-checked every 5 s for up to 30 s): popup with the
  text and the first image, and "MMS fra X, med bilde: tekst" (the text only with *Les også opp
  SMS- og MMS-teksten*). The last MMS id seen is kept, so nothing is announced twice and old messages are never
  read on start-up (the first start marks everything as seen; MMS older than 15 min are skipped).
- **Log tab** – new types `SAMTALE`, `SMS`, `OPPSLAG`, `OPPTAK`, `TTS`. Numbers are masked to the last
  three digits and SMS texts are never logged.

Permissions: Telefon (`READ_PHONE_STATE`), Samtalelogg (`READ_CALL_LOG`), SMS (`READ_SMS`,
`RECEIVE_SMS`, `RECEIVE_MMS`), Kontakter (`READ_CONTACTS`, `WRITE_CONTACTS`), Musikk og lyd
(`READ_MEDIA_AUDIO`), Varsler; Mikrofon (`RECORD_AUDIO`) only for the experimental recorder. On Android
13+ SMS and call-log permissions can be a *restricted setting* for apps installed outside an app store:
if Android says "Restricted setting", open App info (button in Settings), tap ⋮ › *Allow restricted
settings*, then grant them under Permissions.

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
- **Tiered location (1.28+, saves battery).** Uses Google Play services' fused location if available,
  else Android's `LocationManager`. Each check:
  1. **Reuse (`gjenbrukt`)** – the freshest cached fix (fused last location, last-known per provider, and
     fixes other apps got, collected by passive listeners) if it is at most 5 min old, ±100 m or better,
     and newer than the fix used at the previous check (a fix is never used twice, so two checks cannot
     confirm a visit with the same stale fix). Not used for *Check now*.
  2. **Network (`nettverk`)** – one balanced-power fix (Wi-Fi/cell), 20 s timeout, accepted at ±100 m or better.
  3. **GPS** – one high-accuracy fix only if step 2 gave nothing or was too coarse, with a hard 45 s
     timeout (the request is cancelled, so GPS is never left on).
  A fix coarser than ±30 m that would change the place (a candidate or a new visit instead of "still at
  the current visit") is double-checked with GPS before it is applied, so stop detection and the
  Places matching are not fooled by a Wi-Fi fix on a neighbour's address. The thresholds are constants
  in `LocationService`. Every fix is logged in the Log tab (`POSISJON`) with its source, accuracy and
  age; Settings shows how many times GPS was used today. The service no longer holds a permanent wake
  lock (the wake-up alarm drives the checks); each check and each dashboard report hold a short wake
  lock with a timeout, released when done.
  Without any new fix, a recent cached fix not used before (not older than the interval, at least 10
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
      Comms.kt, CommsTab.kt  calls, SMS and MMS (Samtaler tab)
      MmsWatch.kt          new incoming MMS (popup / read aloud)
      MmsMedia.kt          MMS thumbnails, image viewer, open attachments (PartProvider)
      WebLookup.kt, LookupTab.kt         number lookup in a WebView (background, and the Oppslag tab)
    src/main/assets/lookup_extract.js  reads the matching entry from a 1881 / gulesider page
      NumberLookup.kt, Contacts.kt, Phone.kt  number lookup, add to contacts, number formatting
      Announcer.kt, CommReceiver.kt  popup and read-aloud, call / SMS receivers
      Recordings.kt, Player.kt, CallRecorderService.kt  call recordings
    src/main/res/          layout, icons, strings
```

The package name `no.grokbot.stedslogger` is kept from the app's original (Norwegian) name, *Stedslogger*.

## License

Released under the [MIT License](LICENSE).
