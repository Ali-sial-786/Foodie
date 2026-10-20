#!/usr/bin/env bash
# Prints the latest stable versions of the Google Maven / Maven Central artifacts
# this project depends on, so gradle/libs.versions.toml can be kept current.
# Usage: scripts/check-latest-versions.sh
set -uo pipefail

google() {
  local group_path="${1//.//}" artifact="$2"
  local url="https://dl.google.com/dl/android/maven2/${group_path}/${artifact}/maven-metadata.xml"
  local versions
  versions=$(curl -fsSL --max-time 30 "$url" 2>/dev/null | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g' \
    | grep -v -i -E 'alpha|beta|rc|dev|eap|snapshot|preview' | tail -3 | tr '\n' ' ')
  printf '%-55s %s\n' "$1:$2" "${versions:-UNAVAILABLE}"
}

central() {
  local group_path="${1//.//}" artifact="$2"
  local url="https://repo1.maven.org/maven2/${group_path}/${artifact}/maven-metadata.xml"
  local versions
  versions=$(curl -fsSL --max-time 30 "$url" 2>/dev/null | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g' \
    | grep -v -i -E 'alpha|beta|rc|dev|eap|snapshot|preview|-M[0-9]' | tail -3 | tr '\n' ' ')
  printf '%-55s %s\n' "$1:$2" "${versions:-UNAVAILABLE}"
}

echo "== Google Maven"
google com.android.tools.build gradle
google com.android.tools.build aapt2
google androidx.compose compose-bom
google androidx.activity activity-compose
google androidx.core core-ktx
google androidx.lifecycle lifecycle-runtime-compose
google androidx.lifecycle lifecycle-viewmodel-compose
google androidx.navigation navigation-compose
google androidx.camera camera-core
google androidx.camera camera-compose
google androidx.camera camera-view
google androidx.room room-runtime
google androidx.datastore datastore-preferences
google androidx.work work-runtime-ktx
google androidx.browser browser
google androidx.exifinterface exifinterface
google androidx.test.ext junit
google androidx.test core
google androidx.test runner
google androidx.test.espresso espresso-core
google androidx.compose.ui ui-test-junit4
google androidx.compose.material3 material3
google androidx.compose.material material-icons-extended
google androidx.health.connect connect-client
google androidx.profileinstaller profileinstaller
google androidx.benchmark benchmark-macro-junit4
google com.google.mlkit barcode-scanning
google com.google.mlkit text-recognition
google com.google.android.gms play-services-mlkit-barcode-scanning
echo "== Maven Central"
central org.jetbrains.kotlin kotlin-gradle-plugin
central com.google.devtools.ksp com.google.devtools.ksp.gradle.plugin
central org.robolectric robolectric
central io.ktor ktor-client-core
