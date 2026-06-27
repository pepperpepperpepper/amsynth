#!/usr/bin/env bash
#
# Build the Android app (android/) on the "moviecomp" host, which is where the
# Android SDK/NDK and the (self-hosted) F-Droid repo tooling live — the dev
# machine has no NDK. The release APK is fetched back into ./dist, and can
# optionally be published into a local F-Droid repo on that host.
#
# All targets are configurable via environment variables (defaults shown):
#
#   MOVIECOMP_HOST=moviecomp.duckdns.org   # ssh host
#   MOVIECOMP_PORT=22                       # ssh port
#   MOVIECOMP_USER=                         # ssh user (default: your ssh config)
#   REMOTE_DIR=amsynth                      # working clone on the remote ($HOME-relative)
#   GRADLE_VERSION=8.7                      # used if the gradle wrapper is absent
#   FDROID_DIR=                             # if set, `fdroid update` is run there after build
#   LOCAL_OUT=dist                          # where the APK is copied locally
#
# Usage:
#   packaging/android/build-on-moviecomp.sh [git-ref]
#
# [git-ref] defaults to the current branch. The remote clone is created from
# origin on first run, then fetched/checked out to the ref on subsequent runs.
#
# This file is part of amsynth and is licensed under the GNU GPL v2+.

set -euo pipefail

MOVIECOMP_HOST="${MOVIECOMP_HOST:-moviecomp.duckdns.org}"
MOVIECOMP_PORT="${MOVIECOMP_PORT:-22}"
MOVIECOMP_USER="${MOVIECOMP_USER:-}"
REMOTE_DIR="${REMOTE_DIR:-amsynth}"
GRADLE_VERSION="${GRADLE_VERSION:-8.7}"
FDROID_DIR="${FDROID_DIR:-}"
LOCAL_OUT="${LOCAL_OUT:-dist}"

REF="${1:-$(git rev-parse --abbrev-ref HEAD)}"
ORIGIN_URL="$(git config --get remote.origin.url)"

if [ -n "$MOVIECOMP_USER" ]; then
	TARGET="${MOVIECOMP_USER}@${MOVIECOMP_HOST}"
else
	TARGET="$MOVIECOMP_HOST"
fi

echo ">> building ref '$REF' on $TARGET:$MOVIECOMP_PORT (clone: $REMOTE_DIR, origin: $ORIGIN_URL)"

# Run the build remotely. Args are passed positionally into the remote shell.
ssh -p "$MOVIECOMP_PORT" "$TARGET" bash -s -- \
	"$REF" "$ORIGIN_URL" "$REMOTE_DIR" "$GRADLE_VERSION" "$FDROID_DIR" <<'REMOTE'
set -euo pipefail
REF="$1"; ORIGIN_URL="$2"; REMOTE_DIR="$3"; GRADLE_VERSION="$4"; FDROID_DIR="$5"

# Sync the source tree.
if [ ! -d "$REMOTE_DIR/.git" ]; then
	echo ">> cloning $ORIGIN_URL -> $REMOTE_DIR"
	git clone "$ORIGIN_URL" "$REMOTE_DIR"
fi
cd "$REMOTE_DIR"
git fetch --all --prune
git checkout "$REF"
git pull --ff-only origin "$REF" || true   # detached/tag refs won't pull; that's fine

cd android

# The gradle wrapper jar is git-ignored; generate it on first build.
if [ ! -x ./gradlew ]; then
	echo ">> generating gradle wrapper $GRADLE_VERSION"
	gradle wrapper --gradle-version "$GRADLE_VERSION"
fi

echo ">> assembleRelease"
./gradlew --no-daemon assembleRelease

APK="$(ls -t app/build/outputs/apk/release/*.apk | head -1)"
echo ">> built $APK"

if [ -n "$FDROID_DIR" ]; then
	echo ">> publishing into F-Droid repo at $FDROID_DIR"
	mkdir -p "$FDROID_DIR/repo"
	cp "$APK" "$FDROID_DIR/repo/"
	# Pull the fastlane metadata in so the listing is populated.
	mkdir -p "$FDROID_DIR/metadata"
	( cd "$FDROID_DIR" && fdroid update --create-metadata )
fi
REMOTE

# Fetch the APK back.
mkdir -p "$LOCAL_OUT"
echo ">> fetching APK -> $LOCAL_OUT/"
scp -P "$MOVIECOMP_PORT" \
	"$TARGET:$REMOTE_DIR/android/app/build/outputs/apk/release/*.apk" \
	"$LOCAL_OUT/"

echo ">> done. APK(s) in $LOCAL_OUT/:"
ls -la "$LOCAL_OUT/"/*.apk
