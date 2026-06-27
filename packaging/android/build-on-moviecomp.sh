#!/usr/bin/env bash
#
# Build the Android app (android/) on the "moviecomp" host, which is where the
# Android SDK/NDK and the (self-hosted) F-Droid repo tooling live — the dev
# machine has no NDK. The release APK is fetched back into ./dist, and can
# optionally be published into a local F-Droid repo on that host.
#
# moviecomp has the SDK but no system `gradle`, so this bootstraps a pinned
# Gradle into ~/tools on the host the first time.
#
# Configurable via environment variables (defaults shown):
#
#   MOVIECOMP_HOST=moviecomp.duckdns.org    # ssh host
#   MOVIECOMP_PORT=22                        # ssh port
#   MOVIECOMP_USER=arch                      # ssh user
#   MOVIECOMP_KEY=~/.ssh/claude_key          # ssh identity (this host's authorized key)
#   REMOTE_DIR=amsynth                       # working clone on the remote ($HOME-relative)
#   GRADLE_VERSION=8.7
#   FDROID_DIR=                              # if set, `fdroid update` is run there after build
#   LOCAL_OUT=dist                           # where the APK is copied locally
#
# Usage:
#   packaging/android/build-on-moviecomp.sh [git-ref]
#
# [git-ref] defaults to the current branch. The remote clone is created from
# origin on first run (public https), then fetched/checked out on later runs.
#
# This file is part of amsynth and is licensed under the GNU GPL v2+.

set -euo pipefail

MOVIECOMP_HOST="${MOVIECOMP_HOST:-moviecomp.duckdns.org}"
MOVIECOMP_PORT="${MOVIECOMP_PORT:-22}"
MOVIECOMP_USER="${MOVIECOMP_USER:-arch}"
MOVIECOMP_KEY="${MOVIECOMP_KEY:-$HOME/.ssh/claude_key}"
REMOTE_DIR="${REMOTE_DIR:-amsynth}"
GRADLE_VERSION="${GRADLE_VERSION:-8.7}"
FDROID_DIR="${FDROID_DIR:-}"
LOCAL_OUT="${LOCAL_OUT:-dist}"

REF="${1:-$(git rev-parse --abbrev-ref HEAD)}"
ORIGIN_URL="${ORIGIN_URL:-$(git config --get remote.origin.url)}"
# Prefer a clone-friendly https URL for the remote (no deploy key needed there).
case "$ORIGIN_URL" in
	git@github.com:*) CLONE_URL="https://github.com/${ORIGIN_URL#git@github.com:}";;
	*)                CLONE_URL="$ORIGIN_URL";;
esac

TARGET="${MOVIECOMP_USER}@${MOVIECOMP_HOST}"
SSH=(ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "$MOVIECOMP_KEY" -p "$MOVIECOMP_PORT")

echo ">> building ref '$REF' on $TARGET:$MOVIECOMP_PORT (clone: $REMOTE_DIR, url: $CLONE_URL)"

"${SSH[@]}" "$TARGET" bash -s -- \
	"$REF" "$CLONE_URL" "$REMOTE_DIR" "$GRADLE_VERSION" "$FDROID_DIR" <<'REMOTE'
set -euo pipefail
REF="$1"; CLONE_URL="$2"; REMOTE_DIR="$3"; GRADLE_VERSION="$4"; FDROID_DIR="$5"

# SDK location (login shell sets ANDROID_HOME on this host).
: "${ANDROID_HOME:=$HOME/android-sdk}"

# Sync source.
if [ ! -d "$REMOTE_DIR/.git" ]; then
	echo ">> cloning $CLONE_URL -> $REMOTE_DIR"
	git clone "$CLONE_URL" "$REMOTE_DIR"
fi
cd "$REMOTE_DIR"
git fetch --all --prune
git checkout "$REF"
git pull --ff-only origin "$REF" 2>/dev/null || true  # tags/detached won't pull; fine

# AGP reads the SDK path from local.properties (git-ignored).
echo "sdk.dir=$ANDROID_HOME" > android/local.properties

# Resolve a gradle: host-cached -> system -> download a pinned one.
GRADLE_BIN="$HOME/tools/gradle-$GRADLE_VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
	if command -v gradle >/dev/null 2>&1; then
		GRADLE_BIN="$(command -v gradle)"
	else
		echo ">> bootstrapping gradle $GRADLE_VERSION into ~/tools"
		mkdir -p "$HOME/tools" && cd "$HOME/tools"
		wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O g.zip
		unzip -q -o g.zip && rm -f g.zip
		cd - >/dev/null
		GRADLE_BIN="$HOME/tools/gradle-$GRADLE_VERSION/bin/gradle"
	fi
fi
echo ">> using gradle: $GRADLE_BIN"

cd android
"$GRADLE_BIN" --no-daemon --console=plain assembleRelease

APK="$(ls -t app/build/outputs/apk/release/*.apk | head -1)"
echo ">> built $APK"

if [ -n "$FDROID_DIR" ]; then
	echo ">> publishing into F-Droid repo at $FDROID_DIR"
	mkdir -p "$FDROID_DIR/repo"
	cp "$APK" "$FDROID_DIR/repo/"
	( cd "$FDROID_DIR" && fdroid update --create-metadata )
fi
REMOTE

# Fetch the APK back.
mkdir -p "$LOCAL_OUT"
echo ">> fetching APK -> $LOCAL_OUT/"
scp -o BatchMode=yes -o IdentitiesOnly=yes -i "$MOVIECOMP_KEY" -P "$MOVIECOMP_PORT" \
	"$TARGET:$REMOTE_DIR/android/app/build/outputs/apk/release/*.apk" \
	"$LOCAL_OUT/"

echo ">> done. APK(s) in $LOCAL_OUT/:"
ls -la "$LOCAL_OUT"/*.apk
