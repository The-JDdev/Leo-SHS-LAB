#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  LEO DEVOPS — PHASE 4: REPLIT → GITHUB DEPLOYMENT
#  SHS LAB | Auto-creates repo and pushes all source code
# ═══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Credentials (from Replit Secrets) ──
GITHUB_PAT="${GITHUB_TOKEN:?ERROR: GITHUB_TOKEN secret is not set}"
GITHUB_USER="${GITHUB_USER:-The-JDdev}"
REPO_NAME="Leo-SHS-LAB"
REPO_DESC="Official source code for Leo System Agent by SHS LAB."
BRANCH="main"

BOLD="\033[1m"
CYAN="\033[0;36m"
MAGENTA="\033[0;35m"
YELLOW="\033[0;33m"
GREEN="\033[0;32m"
RED="\033[0;31m"
RESET="\033[0m"

log()  { echo -e "${CYAN}[Leo DevOps]${RESET} $1"; }
warn() { echo -e "${YELLOW}[Leo DevOps]${RESET} $1"; }
ok()   { echo -e "${GREEN}[Leo DevOps]${RESET} $1"; }
err()  { echo -e "${RED}[Leo DevOps] ERROR:${RESET} $1"; exit 1; }

echo -e ""
echo -e "${MAGENTA}${BOLD}════════════════════════════════════════${RESET}"
echo -e "${CYAN}${BOLD}  LEO PHASE 4 — GITHUB DEPLOYMENT INIT  ${RESET}"
echo -e "${MAGENTA}${BOLD}════════════════════════════════════════${RESET}"
echo -e ""

# ════════════════════════════════════════════
#  STEP A: REPO CREATION VIA GITHUB REST API
# ════════════════════════════════════════════
log "Checking if repository '${REPO_NAME}' exists..."

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${GITHUB_PAT}" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${GITHUB_USER}/${REPO_NAME}")

if [ "$HTTP_STATUS" = "200" ]; then
    warn "Repository '${REPO_NAME}' already exists — skipping creation."
else
    log "Repository not found (HTTP ${HTTP_STATUS}). Creating private repo..."

    CREATE_RESPONSE=$(curl -s -w "\n%{http_code}" \
      -X POST \
      -H "Authorization: Bearer ${GITHUB_PAT}" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" \
      "https://api.github.com/user/repos" \
      -d "{
        \"name\": \"${REPO_NAME}\",
        \"description\": \"${REPO_DESC}\",
        \"private\": true,
        \"auto_init\": false
      }")

    CREATE_STATUS=$(echo "$CREATE_RESPONSE" | tail -1)
    CREATE_BODY=$(echo "$CREATE_RESPONSE" | sed '$d')

    if [ "$CREATE_STATUS" = "201" ]; then
        ok "Repository '${REPO_NAME}' created successfully (private)."
    else
        # Extract error message
        MSG=$(echo "$CREATE_BODY" | grep -o '"message":"[^"]*"' | head -1 | cut -d'"' -f4)
        err "Repo creation failed (HTTP ${CREATE_STATUS}): ${MSG}"
    fi
fi

# ════════════════════════════════════════════
#  STEP B: GIT OPERATIONS
# ════════════════════════════════════════════
SECURE_REMOTE="https://${GITHUB_PAT}@github.com/${GITHUB_USER}/${REPO_NAME}.git"

log "Configuring Git identity..."
git config --global user.email "leo@shslab.ai"  2>/dev/null || true
git config --global user.name  "Leo SHS LAB"    2>/dev/null || true

# Init if not already a git repo
if [ ! -d ".git" ]; then
    log "Initializing Git repository..."
    git init
fi

log "Switching to branch '${BRANCH}'..."
git checkout -b "${BRANCH}" 2>/dev/null || git checkout "${BRANCH}" 2>/dev/null || true

log "Staging all files..."
# Exclude secrets and build artifacts
cat > .gitignore << 'GITIGNORE'
# Build outputs
android-leo/app/build/
android-leo/.gradle/
android-leo/local.properties
*.keystore
*.jks

# Replit internals
.upm/
.cache/
node_modules/
.local/

# Secrets
.env
*.secret
GITIGNORE

git add .

# Check if there are changes to commit
if git diff --cached --quiet; then
    warn "Nothing new to commit — working tree clean."
else
    log "Committing source code..."
    git commit -m "Auto-deployed Phase 1-3 source code from Replit"
fi

log "Configuring remote origin..."
if git remote get-url origin &>/dev/null; then
    git remote set-url origin "${SECURE_REMOTE}"
    warn "Remote 'origin' already existed — URL updated."
else
    git remote add origin "${SECURE_REMOTE}"
fi

log "Pushing to GitHub (force)..."
git push -u origin "${BRANCH}" --force

# ════════════════════════════════════════════
#  STEP C: SUCCESS LOG
# ════════════════════════════════════════════
echo -e ""
echo -e "${MAGENTA}${BOLD}════════════════════════════════════════════════════════════${RESET}"
echo -e "${GREEN}${BOLD}  [Leo DevOps]: Source code successfully deployed to GitHub! ${RESET}"
echo -e "${CYAN}  Repo: https://github.com/${GITHUB_USER}/${REPO_NAME}${RESET}"
echo -e "${MAGENTA}${BOLD}════════════════════════════════════════════════════════════${RESET}"
echo -e ""
