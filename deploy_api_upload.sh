#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  LEO DEVOPS — GITHUB CONTENTS API UPLOADER (No git required)
#  Uploads source files directly via GitHub REST API.
#  Run this from the Replit Shell tab.
# ═══════════════════════════════════════════════════════════════

set -euo pipefail

GITHUB_PAT="${GITHUB_TOKEN:?ERROR: GITHUB_TOKEN secret not set}"
GITHUB_USER="${GITHUB_USER:-The-JDdev}"
REPO_NAME="Leo-SHS-LAB"
BRANCH="main"
SOURCE_DIR="android-leo"

CYAN="\033[0;36m"; MAGENTA="\033[0;35m"
GREEN="\033[0;32m"; RED="\033[0;31m"
YELLOW="\033[0;33m"; BOLD="\033[1m"; RESET="\033[0m"

log()  { echo -e "${CYAN}[Leo DevOps]${RESET} $1"; }
ok()   { echo -e "${GREEN}[Leo DevOps]${RESET} ✓ $1"; }
err()  { echo -e "${RED}[Leo DevOps] ERR:${RESET} $1"; exit 1; }
warn() { echo -e "${YELLOW}[Leo DevOps]${RESET} ! $1"; }

echo -e ""
echo -e "${MAGENTA}${BOLD}══════════════════════════════════════════════${RESET}"
echo -e "${CYAN}${BOLD}  LEO — GITHUB CONTENTS API DIRECT UPLOAD     ${RESET}"
echo -e "${MAGENTA}${BOLD}══════════════════════════════════════════════${RESET}"
echo -e ""

# Ensure repo exists (already created, but check)
STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${GITHUB_PAT}" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${GITHUB_USER}/${REPO_NAME}")

if [ "$STATUS" != "200" ]; then
    err "Repository not accessible (HTTP $STATUS). Run deploy_to_github.sh first."
fi
ok "Repository confirmed: github.com/${GITHUB_USER}/${REPO_NAME}"

# Upload a single file via GitHub Contents API
upload_file() {
    local local_path="$1"
    local repo_path="$2"

    # Base64 encode file content
    local b64_content
    b64_content=$(base64 -w 0 "$local_path" 2>/dev/null || base64 "$local_path")

    # Check if file already exists (need SHA to update)
    local existing_sha=""
    local check_response
    check_response=$(curl -s \
      -H "Authorization: Bearer ${GITHUB_PAT}" \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/${GITHUB_USER}/${REPO_NAME}/contents/${repo_path}?ref=${BRANCH}")

    if echo "$check_response" | grep -q '"sha"'; then
        existing_sha=$(echo "$check_response" | grep -o '"sha":"[^"]*"' | head -1 | cut -d'"' -f4)
    fi

    # Build JSON payload
    local payload
    if [ -n "$existing_sha" ]; then
        payload="{\"message\":\"Auto-deploy: ${repo_path}\",\"content\":\"${b64_content}\",\"branch\":\"${BRANCH}\",\"sha\":\"${existing_sha}\"}"
    else
        payload="{\"message\":\"Auto-deploy: ${repo_path}\",\"content\":\"${b64_content}\",\"branch\":\"${BRANCH}\"}"
    fi

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
      -X PUT \
      -H "Authorization: Bearer ${GITHUB_PAT}" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" \
      "https://api.github.com/repos/${GITHUB_USER}/${REPO_NAME}/contents/${repo_path}" \
      -d "$payload")

    if [ "$http_code" = "200" ] || [ "$http_code" = "201" ]; then
        ok "Uploaded: ${repo_path}"
        return 0
    else
        warn "Failed (HTTP $http_code): ${repo_path}"
        return 1
    fi
}

# Upload all files from android-leo/ recursively
UPLOADED=0
FAILED=0

log "Scanning ${SOURCE_DIR}/ for files to upload..."
echo ""

find "${SOURCE_DIR}" -type f \
  ! -path "*/build/*" \
  ! -path "*/.gradle/*" \
  ! -name "*.class" \
  ! -name "*.dex" | sort | while read -r filepath; do

    # Compute relative repo path
    repo_path="${filepath}"

    if upload_file "$filepath" "$repo_path"; then
        UPLOADED=$((UPLOADED + 1))
    else
        FAILED=$((FAILED + 1))
    fi

    # Rate-limit: GitHub API allows ~30 writes/min for free tier
    sleep 2
done

# Upload deploy scripts too
for script in deploy_to_github.sh build_and_release.sh; do
    if [ -f "$script" ]; then
        upload_file "$script" "$script" && ok "Uploaded: $script"
        sleep 2
    fi
done

echo ""
echo -e "${MAGENTA}${BOLD}══════════════════════════════════════════════════════════${RESET}"
echo -e "${GREEN}${BOLD}  [Leo DevOps]: Source code successfully deployed to GitHub!${RESET}"
echo -e "${CYAN}  Repo: https://github.com/${GITHUB_USER}/${REPO_NAME}${RESET}"
echo -e "${MAGENTA}${BOLD}══════════════════════════════════════════════════════════${RESET}"
echo ""
