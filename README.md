# Location Logger

A simple Android app that logs your current street address every 5 minutes if it has changed.

## Features

- ✅ Logs street address every 5 minutes
- ✅ Only records when location changes
- ✅ Timestamps each entry in `location.txt`
- ✅ Auto-enables location services if disabled
- ✅ Runs in the background as a service
- ✅ Uses Google Play Services for accurate location
- ✅ Reverse-geocodes coordinates to readable addresses

## Building

### Option 1: Local Build (Android Studio)
1. Clone the repository
2. Open in Android Studio
3. Build → Build APK(s)
4. APK will be in `app/build/outputs/apk/debug/`

### Option 2: GitHub Actions (Automated)
1. Push to a GitHub repository
2. Actions automatically builds the APK
3. Download from the workflow artifacts or releases

### Option 3: Command Line
```bash
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Installation

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the APK file on your device to install.

## Permissions

The app requires:
- `ACCESS_FINE_LOCATION` - Precise GPS location
- `ACCESS_COARSE_LOCATION` - Network-based location  
- `INTERNET` - To fetch geocoding data

Grant these permissions when prompted.

## Usage

1. Launch the app
2. If location services are disabled, tap "Enable Location" to activate
3. Tap "Start Logging" to begin tracking
4. Your addresses are saved to `location.txt` in the app's storage
5. Tap "Stop Logging" to stop tracking

## Log File Location

The log file is saved at:
```
/sdcard/Android/data/com.example.locationlogger/files/location.txt
```

Example log output:
```
[2024-01-15 14:30:22] 123 Main Street, Springfield, IL 62701, USA
[2024-01-15 14:35:45] 456 Oak Avenue, Springfield, IL 62702, USA
[2024-01-15 14:45:10] 789 Elm Drive, Shelbyville, IL 62703, USA
```

## Requirements

- Android 8.0 (API 26) or higher
- Google Play Services installed
- Location services enabled
- GPS or network-based location enabled

## Architecture

- **MainActivity**: UI with Start/Stop buttons
- **LocationLoggerService**: Background service that handles location updates and logging
- Updates check every 5 minutes (300,000 ms)
- Uses Geocoder to reverse-geocode coordinates to addresses

## License

MIT
