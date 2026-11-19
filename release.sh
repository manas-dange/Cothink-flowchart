#!/usr/bin/env bash
set -e

TAG=$1
NOTES=$2

if [ -z "$TAG" ]; then
    echo "Usage: ./release.sh <tag> [release_notes]"
    echo "Example: ./release.sh v1.0.1 'Fix diagram rendering and mic sensitivity'"
    exit 1
fi

if [ -z "$NOTES" ]; then
    NOTES="Release $TAG"
fi

echo "==> 1. Building APK..."
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
ASSET_NAME="CoThinker-${TAG}.apk"
cp "$APK_PATH" "/tmp/${ASSET_NAME}"

echo "==> 2. Committing and Pushing..."
git add -A
git commit -m "Release $TAG" || true
git push origin main

echo "==> 3. Creating GitHub Release ${TAG}..."
gh release create "$TAG" "/tmp/${ASSET_NAME}" --title "CoThinker ${TAG}" --notes "$NOTES"

echo "==> Done! Release published at:"
echo "https://github.com/manas-dange/Cothink-flowchart/releases/tag/${TAG}"
echo "Direct APK download:"
echo "https://github.com/manas-dange/Cothink-flowchart/releases/download/${TAG}/${ASSET_NAME}"
