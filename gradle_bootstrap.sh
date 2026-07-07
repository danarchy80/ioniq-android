#!/bin/bash
# Gradle bootstrap — sets up JDK 17 + Android SDK environment, then runs Gradle
set -e

export JAVA_HOME="$HOME/dev-tools/jdk-17.0.12"
export PATH="$JAVA_HOME/bin:$PATH"

export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"

cd "$HOME/ioniq-android"

echo "=== Environment ==="
echo "JAVA_HOME=$JAVA_HOME"
echo "java=$(java -version 2>&1 | head -1)"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "==================="

./gradlew "$@"
