#!/usr/bin/env bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
GRADLE_BIN="/Users/manasdange/.gradle/wrapper/dists/gradle-9.3.1-all/9ot9r568e8zfvvd4mn8rbu1j0/gradle-9.3.1/bin/gradle"
exec "$GRADLE_BIN" -g /tmp/gradle-home "$@"
