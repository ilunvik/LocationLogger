# Install Location Logger on Your Phone

Your complete app source code is ready. Build and install it using one of these methods:

---

## Method 1: Build in Termux (Recommended) ⭐

### Requirements:
- Termux app installed on your phone
- Internet connection
- ~200 MB free storage

### Steps:

1. **Open Termux** on your phone

2. **Update packages**:
   ```bash
   pkg update && pkg upgrade
   ```

3. **Install build tools**:
   ```bash
   pkg install gradle openjdk-21 git
   ```

4. **Clone and build**:
   ```bash
   git clone https://github.com/ilunvik/LocationLogger.git
   cd LocationLogger
   chmod +x gradlew
   ./gradlew :app:assembleDebug -x lint
   ```

5. **Install the APK**:
   ```bash
   pm install -r app/build/outputs/apk/debug/app-debug.apk
   ```

**Build time**: 3-5 minutes on first build

---

## Method 2: Use Android Studio on PC (Most Reliable)

### Requirements:
- PC/Mac with Android Studio installed
- USB cable (optional, can transfer APK via file)

### Steps:

1. **Clone repository**:
   ```bash
   git clone https://github.com/ilunvik/LocationLogger.git
   ```

2. **Open in Android Studio**:
   - File → Open → LocationLogger folder
   - Wait for Gradle sync to complete

3. **Build APK**:
   - Build → Build Bundle(s)/APK(s) → Build APK(s)

4. **Install on phone**:
   - Connect phone via USB
   - Run → Select your device
   - OR transfer APK via email/file transfer and tap to install

---

## Method 3: Use Online Build Service

**Codemagic** (Free tier available):
1. Visit: https://codemagic.io
2. Sign in with GitHub
3. Connect LocationLogger repository
4. Click "Start building"
5. Download APK when ready (5-10 minutes)
6. Transfer to phone and install

---

## Method 4: Build on Another Phone/Device

If you have another Android phone with Termux:
1. Follow **Method 1**
2. Transfer APK to your main phone
3. Install with: `pm install LocationLogger-debug.apk`

---

## Installation Verification

After installing, check:

✅ **App appears in your app drawer** - named "Location Logger"
✅ **Tap app to open** - should show Start/Stop buttons
✅ **Grant location permissions** when prompted
✅ **Enable location services** if asked

---

## First Run

1. **Open the app**
2. **Tap "Start Logging"**
3. **Grant permissions** as requested
4. **Enable location** if prompted
5. **App starts tracking** your location every 5 minutes

Check your logs:
- File: `/sdcard/Android/data/com.example.locationlogger/files/location.txt`
- Via Termux: `cat /sdcard/Android/data/com.example.locationlogger/files/location.txt`

---

## Troubleshooting

### "gradle: command not found"
```bash
pkg install gradle
```

### "java: command not found"
```bash
pkg install openjdk-21
```

### Build fails with "Module not specified"
```bash
./gradlew :app:assembleDebug -x lint
```

### App won't install
```bash
# Check if already installed
pm list packages | grep locationlogger

# Uninstall first if needed
pm uninstall com.example.locationlogger

# Then install fresh
pm install -r app/build/outputs/apk/debug/app-debug.apk
```

### Location not working
- Enable location services in Settings
- Grant Location permission to app
- Ensure GPS/Network location is active

---

## GitHub Repository

All source code available at:
**https://github.com/ilunvik/LocationLogger**

---

## App Features

✅ Logs street address every 5 minutes
✅ Only records when location changes  
✅ Timestamps each entry
✅ Auto-enables location services
✅ Runs in background
✅ Saves to `location.txt`

---

## Questions?

Refer to the TERMUX_BUILD_INSTRUCTIONS.md for detailed steps, or BUILD_IN_TERMUX.sh for an automated script.

Good luck! 🚀
