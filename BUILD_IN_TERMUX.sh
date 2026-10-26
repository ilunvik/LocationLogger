#!/bin/bash

# Location Logger - Build Script for Termux
# Run this in Termux to build the APK

echo "📦 Building Location Logger App in Termux"
echo "========================================"
echo ""

# Check if we're in Termux
if [ ! -d "$PREFIX" ]; then
    echo "❌ This script must be run in Termux!"
    exit 1
fi

echo "✓ Running in Termux"
echo ""

# Install dependencies if needed
echo "Checking dependencies..."

if ! command -v gradle &> /dev/null; then
    echo "Installing gradle..."
    pkg install -y gradle
fi

if ! command -v java &> /dev/null; then
    echo "Installing Java..."
    pkg install -y openjdk-21
fi

echo "✓ Dependencies ready"
echo ""

# Clean build
echo "🧹 Cleaning previous builds..."
rm -rf app/build build .gradle
chmod +x gradlew
echo "✓ Clean complete"
echo ""

# Build APK
echo "🔨 Building APK..."
echo ""

./gradlew :app:assembleDebug -x lint

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ BUILD SUCCESSFUL!"
    echo ""
    echo "APK Location:"
    APK_PATH="$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
    echo "  $APK_PATH"
    echo ""
    echo "File size:"
    ls -lh "$APK_PATH" | awk '{print "  " $5}'
    echo ""
    echo "Next steps:"
    echo "  1. Transfer APK to your phone"
    echo "  2. Tap to install"
    echo "  3. Or use: pm install -r $APK_PATH"
else
    echo ""
    echo "❌ BUILD FAILED"
    echo "Check the errors above"
    exit 1
fi
