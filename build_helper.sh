#!/bin/bash
export JAVA_HOME=/data/local/tmp/androidharness/com.androidharness.app/linux/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH

cd /storage/emulated/0/Android/data/com.androidharness.app/files/workspace

echo "🔨 Building Location Logger..."
echo ""

chmod +x gradlew
./gradlew --version

