#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  LEO BUILD MASTER — PHASE 5: LOCAL APK BUILD + GITHUB RELEASE
#  SHS LAB | Builds APK inside Replit, uploads via REST API.
#  NO GitHub Actions. NO Codespaces. Pure local build.
# ═══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Credentials ──
GITHUB_PAT="${GITHUB_TOKEN:?ERROR: GITHUB_TOKEN secret is not set}"
GITHUB_USER="${GITHUB_USER:-The-JDdev}"
REPO_NAME="Leo-SHS-LAB"
RELEASE_TAG="v1.0-alpha"
RELEASE_NAME="Leo First Blood"
RELEASE_BODY="Phase 1-3 complete. Leo System Agent — SHS LAB. First local Replit build."

ANDROID_DIR="android-leo"
APK_DEBUG="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
APK_UNIVERSAL="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug-universal.apk"

BOLD="\033[1m"
CYAN="\033[0;36m"
MAGENTA="\033[0;35m"
YELLOW="\033[0;33m"
GREEN="\033[0;32m"
RED="\033[0;31m"
RESET="\033[0m"

log()  { echo -e "${CYAN}[Leo Build Master]${RESET} $1"; }
warn() { echo -e "${YELLOW}[Leo Build Master]${RESET} $1"; }
ok()   { echo -e "${GREEN}[Leo Build Master]${RESET} $1"; }
err()  { echo -e "${RED}[Leo Build Master] ERROR:${RESET} $1"; exit 1; }

echo -e ""
echo -e "${MAGENTA}${BOLD}═══════════════════════════════════════════════${RESET}"
echo -e "${CYAN}${BOLD}  LEO PHASE 5 — LOCAL BUILD & GITHUB RELEASE   ${RESET}"
echo -e "${MAGENTA}${BOLD}═══════════════════════════════════════════════${RESET}"
echo -e ""

# ════════════════════════════════════════════
#  STEP A: LOCAL GRADLE BUILD
# ════════════════════════════════════════════
log "Entering Android project directory: ${ANDROID_DIR}"
cd "${ANDROID_DIR}"

# Ensure gradlew is executable
chmod +x gradlew 2>/dev/null || true

log "Cleaning previous build artifacts..."
./gradlew clean --quiet --no-daemon 2>&1 | tail -5 || true

log "Building APK variants (debug)..."
log "  ├─ armeabi-v7a"
log "  ├─ arm64-v8a"
log "  ├─ x86"
log "  ├─ x86_64"
log "  └─ universal"
echo -e ""

# Build universal debug APK (covers all ABIs in one file)
./gradlew assembleDebug \
  --no-daemon \
  --max-workers=2 \
  --gradle-user-home /tmp/.gradle_home \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=256m" \
  -Dorg.gradle.parallel=false \
  -Dorg.gradle.configureondemand=false \
  2>&1

# ── Verify APK exists ──
cd ..
if [ ! -f "${APK_DEBUG}" ]; then
    err "Build failed — APK not found at: ${APK_DEBUG}"
fi

APK_SIZE=$(du -sh "${APK_DEBUG}" | cut -f1)
ok "APK compiled successfully: ${APK_DEBUG} (${APK_SIZE})"

# ════════════════════════════════════════════
#  STEP B: CREATE GITHUB RELEASE VIA REST API
# ════════════════════════════════════════════
log "Creating GitHub Release '${RELEASE_NAME}' (${RELEASE_TAG})..."

RELEASE_RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST \
  -H "Authorization: Bearer ${GITHUB_PAT}" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" \
  "https://api.github.com/repos/${GITHUB_USER}/${REPO_NAME}/releases" \
  -d "{
    \"tag_name\": \"${RELEASE_TAG}\",
    \"target_commitish\": \"main\",
    \"name\": \"${RELEASE_NAME}\",
    \"body\": \"${RELEASE_BODY}\",
    \"draft\": false,
    \"prerelease\": true
  }")

RELEASE_STATUS=$(echo "$RELEASE_RESPONSE" | tail -1)
RELEASE_BODY_JSON=$(echo "$RELEASE_RESPONSE" | sed '$d')

if [ "$RELEASE_STATUS" != "201" ]; then
    # Check if release already exists (422 = tag already exists)
    if [ "$RELEASE_STATUS" = "422" ]; then
        warn "Release tag '${RELEASE_TAG}' already exists. Fetching existing release..."
        RELEASE_BODY_JSON=$(curl -s \
          -H "Authorization: Bearer ${GITHUB_PAT}" \
          -H "Accept: application/vnd.github+json" \
          "https://api.github.com/repos/${GITHUB_USER}/${REPO_NAME}/releases/tags/${RELEASE_TAG}")
    else
        MSG=$(echo "$RELEASE_BODY_JSON" | grep -o '"message":"[^"]*"' | head -1 | cut -d'"' -f4)
        err "Release creation failed (HTTP ${RELEASE_STATUS}): ${MSG}"
    fi
fi

ok "Release created (HTTP ${RELEASE_STATUS})"

# Extract upload_url — strip the {?name,label} template suffix
RAW_UPLOAD_URL=$(echo "$RELEASE_BODY_JSON" | grep -o '"upload_url":"[^"]*"' | head -1 | cut -d'"' -f4)
UPLOAD_URL="${RAW_UPLOAD_URL%%\{*}"

if [ -z "${UPLOAD_URL}" ]; then
    err "Could not extract upload_url from release response."
fi

log "Upload URL: ${UPLOAD_URL}"

# ════════════════════════════════════════════
#  STEP C: UPLOAD APK TO GITHUB RELEASE
# ════════════════════════════════════════════
APK_ASSET_NAME="Leo-SHS-LAB-${RELEASE_TAG}-debug.apk"

log "Uploading APK asset: ${APK_ASSET_NAME} (${APK_SIZE})..."

UPLOAD_RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST \
  -H "Authorization: Bearer ${GITHUB_PAT}" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/vnd.android.package-archive" \
  "${UPLOAD_URL}?name=${APK_ASSET_NAME}&label=Leo+Debug+APK" \
  --data-binary "@${APK_DEBUG}")

UPLOAD_STATUS=$(echo "$UPLOAD_RESPONSE" | tail -1)
UPLOAD_BODY=$(echo "$UPLOAD_RESPONSE" | sed '$d')

if [ "$UPLOAD_STATUS" = "201" ]; then
    DOWNLOAD_URL=$(echo "$UPLOAD_BODY" | grep -o '"browser_download_url":"[^"]*"' | head -1 | cut -d'"' -f4)
    ok "APK uploaded successfully!"
    log "Download URL: ${DOWNLOAD_URL}"
else
    MSG=$(echo "$UPLOAD_BODY" | grep -o '"message":"[^"]*"' | head -1 | cut -d'"' -f4)
    err "APK upload failed (HTTP ${UPLOAD_STATUS}): ${MSG}"
fi

# ════════════════════════════════════════════
#  FINAL SUCCESS LOG
# ════════════════════════════════════════════
echo -e ""
echo -e "${MAGENTA}${BOLD}═══════════════════════════════════════════════════════════════════════${RESET}"
echo -e "${GREEN}${BOLD}  [Leo Build Master]: Local compilation complete!                      ${RESET}"
echo -e "${GREEN}${BOLD}  APK successfully uploaded to GitHub Releases.                        ${RESET}"
echo -e "${CYAN}${BOLD}  System ready for JD.                                                 ${RESET}"
echo -e "${MAGENTA}${BOLD}═══════════════════════════════════════════════════════════════════════${RESET}"
echo -e "${YELLOW}  Release: https://github.com/${GITHUB_USER}/${REPO_NAME}/releases/tag/${RELEASE_TAG}${RESET}"
echo -e ""
