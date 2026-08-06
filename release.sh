#!/usr/bin/env bash
# Cuts a release: builds a signed APK, tags it, and publishes it to GitHub so
# the in-app updater can find it.
#
# The tag carries the versionCode after a '+' because that is what the app
# compares — semver strings alone sort wrongly ("0.10.0" vs "0.9.0").
set -euo pipefail
cd "$(dirname "$0")"

# Find a working JDK without depending on the caller's shell profile: cron, CI
# and non-login shells do not source ~/.zshrc.
#
# The test runs java rather than merely locating it. macOS ships a /usr/bin/java
# stub that exists, is executable, and does nothing but print "Unable to locate
# a Java Runtime" — so a `command -v java` check passes while java is unusable.
java_works() { "${1:-java}" -version >/dev/null 2>&1; }

if ! java_works "${JAVA_HOME:+$JAVA_HOME/bin/java}"; then
  for candidate in /opt/homebrew/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk; do
    if java_works "$candidate/bin/java"; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi
if ! java_works; then
  echo "No working JDK found. Install one (brew install openjdk@17) or set JAVA_HOME." >&2
  exit 1
fi

VERSION_NAME=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)
VERSION_CODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | awk '{print $3}')
TAG="v${VERSION_NAME}+${VERSION_CODE}"

if [ ! -f keystore.properties ]; then
  echo "keystore.properties missing — the release would be unsigned and could not" >&2
  echo "install over the copy already on your phone. Aborting." >&2
  exit 1
fi

echo "Building ${TAG}…"
# The *github* flavour, which is the one with the in-app updater in it. The Play
# build has neither the updater nor the permission it needs — see the
# `distribution` flavours in app/build.gradle.kts — so publishing that one here
# would ship a release the app cannot update itself to.
./gradlew --quiet :app:testGithubDebugUnitTest :app:assembleGithubRelease

APK="app/build/outputs/apk/github/release/app-github-release.apk"
# A fixed name, so `releases/latest/download/MyWallet.apk` always resolves. The
# app finds its update through that URL rather than through the GitHub API,
# which allows an anonymous caller only 60 requests an hour per IP address —
# shared with everything else on the same network, and easily exhausted by a
# few releases in an afternoon. The releases page still says which version this
# is; the file no longer has to.
OUT="MyWallet.apk"
cp "$APK" "$OUT"

# What the app reads to decide whether there is anything newer. Tiny, and served
# from the same redirect, so a check costs one request and no quota.
cat > latest.json <<JSON
{
  "versionName": "${VERSION_NAME}",
  "versionCode": ${VERSION_CODE},
  "notes": $(printf '%s' "${1:-Maintenance release.}" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')
}
JSON

git tag -f "$TAG"
git push origin main --tags --force-with-lease

gh release create "$TAG" "$OUT" latest.json \
  --title "My Money Tracker ${VERSION_NAME}" \
  --notes "${1:-Maintenance release.}" \
  || gh release upload "$TAG" "$OUT" latest.json --clobber

rm -f "$OUT" latest.json
echo "Published ${TAG}. Open Settings in the app and check for updates."
