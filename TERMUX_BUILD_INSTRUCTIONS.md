# Build Location Logger in Termux

## Quick Start

### Step 1: Clone the Repository in Termux

Open Termux and run:

```bash
git clone https://github.com/ilunvik/LocationLogger.git
cd LocationLogger
```

### Step 2: Install Dependencies (if needed)

```bash
pkg update
pkg install gradle openjdk-21 git
```

### Step 3: Build the APK

Run the build script:

```bash
bash BUILD_IN_TERMUX.sh
```

Or manually:

```bash
chmod +x gradlew
./gradlew :app:assembleDebug -x lint
```

### Step 4: Find Your APK

The APK will be created at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Step 5: Install on Your Phone

**Option A: Using Termux**
```bash
pm install -r app/build/outputs/apk/debug/app-debug.apk
```

**Option B: File Manager**
1. Open file manager
2. Navigate to: `app/build/outputs/apk/debug/`
3. Tap `app-debug.apk` to install

**Option C: ADB (if connected to PC)**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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

### "Permission denied"
```bash
chmod +x gradlew
```

### Build takes too long
The first build takes 3-5 minutes. Subsequent builds are faster.

### Out of memory
Free up RAM and run:
```bash
./gradlew :app:assembleDebug -x lint --no-daemon
```

---

## Build Configuration

- **Android Gradle Plugin**: 3.6.4
- **Gradle**: 5.6.4
- **Java**: Openjdk-21
- **Min SDK**: 16
- **Target SDK**: 28
- **Build Type**: Debug (unsigned)

---

## Success Indicators

✅ Build complete message  
✅ APK file created (~5-10 MB)  
✅ No errors in output  

---

## What the App Does

- Logs your street address every 5 minutes
- Only records when location changes
- Timestamps each entry in location.txt
- Auto-enables location services
- Runs in background
- Saves to app's external storage

---

Good luck! Let me know if you hit any issues.
