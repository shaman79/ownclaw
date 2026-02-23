#!/usr/bin/env bash
# =============================================================================
# OwnClaw Deploy / Auto-Update Script
# =============================================================================
# Usage:
#   ./deploy.sh              # Full deploy (first time or force)
#   ./deploy.sh --update     # Only deploy if there are new commits (for cron)
#   ./deploy.sh --setup      # First-time server setup (run once, as root)
#   ./deploy.sh --rollback   # Restore previous JAR
#
# Authentication:
#   During --setup, you will be prompted for your GitHub token interactively.
#   The token is saved to /opt/ownclaw/.env for subsequent cron-based updates.
#
# Cron example (check for updates every 15 minutes):
#   */15 * * * * /opt/ownclaw/deploy/deploy.sh --update >> /opt/ownclaw/logs/deploy.log 2>&1
# =============================================================================
set -euo pipefail

# === Configuration ===
DEPLOY_DIR="/opt/ownclaw"
REPO_DIR="${DEPLOY_DIR}/repo"

# Load .env if present (picks up GITHUB_TOKEN and other vars)
if [ -f "$DEPLOY_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "$DEPLOY_DIR/.env"
    set +a
fi

# Build repo URL — with token auth if GITHUB_TOKEN is set
GITHUB_REPO="github.com/shaman79/ownclaw.git"
if [ -n "${GITHUB_TOKEN:-}" ]; then
    REPO_URL="https://x-access-token:${GITHUB_TOKEN}@${GITHUB_REPO}"
else
    REPO_URL="https://${GITHUB_REPO}"
fi
BRANCH="main"
SERVICE_NAME="ownclaw"
JDK_VERSION="21"
JDK_DIR="${DEPLOY_DIR}/jdk"
LOG_DIR="${DEPLOY_DIR}/logs"
BACKUP_DIR="${DEPLOY_DIR}/backups"
MAX_BACKUPS=5
HEALTH_URL="http://localhost:8080/api/health"
HEALTH_TIMEOUT=60

# === Helpers ===
timestamp() { date '+%Y-%m-%d %H:%M:%S'; }
log()  { echo "[$(timestamp)] $*" >&2; }
die()  { log "ERROR: $*"; exit 1; }

# === First-time server setup (run as root) ===
do_setup() {
    log "=== OwnClaw Server Setup ==="

    if [ "$(id -u)" -ne 0 ]; then
        die "Setup must be run as root: sudo ./deploy.sh --setup"
    fi

    # Prompt for GITHUB_TOKEN interactively if not already set
    if [ -z "${GITHUB_TOKEN:-}" ]; then
        echo ""
        echo "  GitHub Personal Access Token is required to clone the repository."
        echo "  Generate a fine-grained token at: https://github.com/settings/tokens?type=beta"
        echo "  Required permission: Contents → Read-only (select the ownclaw repo)"
        echo ""
        read -r -p "  Paste your GitHub token: " GITHUB_TOKEN
        echo ""
        if [ -z "$GITHUB_TOKEN" ]; then
            die "No token provided. Cannot continue."
        fi
        # Rebuild repo URL with token
        REPO_URL="https://x-access-token:${GITHUB_TOKEN}@${GITHUB_REPO}"
    fi

    # Create service user
    if ! id -u ownclaw &>/dev/null; then
        log "Creating ownclaw user..."
        useradd --system --home-dir "$DEPLOY_DIR" --shell /usr/sbin/nologin ownclaw
    fi

    # Create directory structure
    log "Creating directories..."
    mkdir -p "$DEPLOY_DIR"/{data,logs,backups,skills/_envs,skills/core,skills/generated}
    mkdir -p "$REPO_DIR"

    # Install JDK 21
    install_jdk

    # Install git if missing
    if ! command -v git &>/dev/null; then
        log "Installing git..."
        apt-get update -qq && apt-get install -y -qq git
    fi

    # Install Python 3 if missing (needed for skills)
    if ! command -v python3 &>/dev/null; then
        log "Installing Python 3..."
        apt-get update -qq && apt-get install -y -qq python3 python3-venv
    fi

    # Clone or update repo
    git config --global --add safe.directory "$REPO_DIR" 2>/dev/null || true
    if [ ! -d "$REPO_DIR/.git" ]; then
        log "Cloning repository..."
        git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
    else
        log "Updating repository..."
        cd "$REPO_DIR"
        git fetch origin "$BRANCH" --depth 1 --quiet
        git reset --hard "origin/$BRANCH" --quiet
    fi

    # Create .env from template if it doesn't exist
    if [ ! -f "$DEPLOY_DIR/.env" ]; then
        log "Creating .env from template..."
        cp "$REPO_DIR/deploy/.env.template" "$DEPLOY_DIR/.env"

        # Inject the GitHub token
        sed -i "s|^GITHUB_TOKEN=.*|GITHUB_TOKEN=${GITHUB_TOKEN}|" "$DEPLOY_DIR/.env"

        # Prompt for OpenAI key
        echo ""
        echo "  OpenAI API key is required for the Mentor LLM (GPT-4o)."
        echo "  Get one at: https://platform.openai.com/api-keys"
        echo ""
        read -r -p "  Paste your OpenAI API key (or press Enter to skip): " openai_key
        if [ -n "$openai_key" ]; then
            sed -i "s|^OPENAI_API_KEY=.*|OPENAI_API_KEY=${openai_key}|" "$DEPLOY_DIR/.env"
        fi

        # Prompt for Telegram bot token
        echo ""
        read -r -p "  Paste your Telegram bot token (or press Enter to skip): " telegram_token
        if [ -n "$telegram_token" ]; then
            sed -i "s|^# TELEGRAM_BOT_TOKEN=.*|TELEGRAM_BOT_TOKEN=${telegram_token}|" "$DEPLOY_DIR/.env"
            sed -i "s|^# OWNCLAW_TELEGRAM_ENABLED=.*|OWNCLAW_TELEGRAM_ENABLED=true|" "$DEPLOY_DIR/.env"
        fi
        echo ""

        chmod 600 "$DEPLOY_DIR/.env"
        log "Secrets saved to $DEPLOY_DIR/.env"
    fi

    # Install systemd service
    log "Installing systemd service..."
    cp "$REPO_DIR/deploy/ownclaw.service" /etc/systemd/system/ownclaw.service
    systemctl daemon-reload
    systemctl enable ownclaw

    # Fix ownership and permissions
    chown -R ownclaw:ownclaw "$DEPLOY_DIR"
    chmod +x "$REPO_DIR/deploy/deploy.sh"
    chmod +x "$REPO_DIR/gradlew"

    # Initial build and deploy
    log "Running initial build..."
    su -s /bin/bash ownclaw -c "$REPO_DIR/deploy/deploy.sh"

    log ""
    log "=== Setup Complete ==="
    log "1. Review secrets:  sudo nano $DEPLOY_DIR/.env"
    log "2. Start service:   sudo systemctl start ownclaw"
    log "3. Check status:    sudo systemctl status ownclaw"
    log "4. View logs:       sudo journalctl -u ownclaw -f"
    log ""
    log "For auto-updates, add to crontab (sudo crontab -u ownclaw -e):"
    log "  */15 * * * * $REPO_DIR/deploy/deploy.sh --update >> $LOG_DIR/deploy.log 2>&1"
}

# === Install JDK 21 (Adoptium Temurin) ===
install_jdk() {
    if [ -x "$JDK_DIR/bin/java" ]; then
        local ver
        ver=$("$JDK_DIR/bin/java" -version 2>&1 | head -1)
        if echo "$ver" | grep -q "\"21\."; then
            log "JDK 21 already installed: $ver"
            return 0
        fi
    fi

    log "Installing JDK 21 (Adoptium Temurin)..."
    local arch
    arch=$(dpkg --print-architecture 2>/dev/null || echo "amd64")
    local jdk_arch="x64"
    [ "$arch" = "arm64" ] && jdk_arch="aarch64"

    local tmp_tar
    tmp_tar=$(mktemp /tmp/jdk21-XXXXXX.tar.gz)

    local jdk_url="https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/linux/${jdk_arch}/jdk/hotspot/normal/eclipse"
    log "Downloading from Adoptium..."
    curl -fsSL -o "$tmp_tar" "$jdk_url" || die "Failed to download JDK"

    rm -rf "$JDK_DIR"
    mkdir -p "$JDK_DIR"
    tar xzf "$tmp_tar" -C "$JDK_DIR" --strip-components=1
    rm -f "$tmp_tar"

    log "JDK installed: $("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
}

# === Pull latest code, return 0 if there are changes ===
pull_latest() {
    if [ ! -d "$REPO_DIR/.git" ]; then
        log "Cloning repository..."
        git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
        return 0
    fi

    cd "$REPO_DIR"

    # Fetch and check for changes
    local before
    before=$(git rev-parse HEAD)

    git fetch origin "$BRANCH" --depth 1 --quiet
    local after
    after=$(git rev-parse "origin/$BRANCH")

    if [ "$before" = "$after" ]; then
        return 1  # No changes
    fi

    log "New commits: ${before:0:8} -> ${after:0:8}"
    git reset --hard "origin/$BRANCH" --quiet
    return 0
}

# === Build the JAR ===
build_jar() {
    log "Building OwnClaw..."
    cd "$REPO_DIR"

    export JAVA_HOME="$JDK_DIR"
    export GRADLE_USER_HOME="$DEPLOY_DIR/.gradle"
    mkdir -p "$GRADLE_USER_HOME" 2>/dev/null || true
    chmod +x gradlew 2>/dev/null || true
    ./gradlew build -x test --no-daemon >&2

    local jar="$REPO_DIR/build/libs/ownclaw-0.1.0.jar"
    [ -f "$jar" ] || die "Build failed — JAR not found at $jar"
    log "Build successful: $(du -h "$jar" | cut -f1)"
    echo "$jar"
}

# === Backup current JAR ===
backup_current() {
    local current="$DEPLOY_DIR/ownclaw.jar"
    [ -f "$current" ] || return 0

    mkdir -p "$BACKUP_DIR"
    local ts
    ts=$(date '+%Y%m%d-%H%M%S')
    cp "$current" "$BACKUP_DIR/ownclaw-${ts}.jar"
    log "Backed up current JAR to ownclaw-${ts}.jar"

    # Prune old backups
    local count
    count=$(ls -1 "$BACKUP_DIR"/ownclaw-*.jar 2>/dev/null | wc -l)
    if [ "$count" -gt "$MAX_BACKUPS" ]; then
        ls -1t "$BACKUP_DIR"/ownclaw-*.jar | tail -n +$((MAX_BACKUPS + 1)) | xargs rm -f
        log "Pruned old backups (keeping $MAX_BACKUPS)"
    fi
}

# === Deploy the new JAR and restart ===
deploy_jar() {
    local new_jar="$1"

    backup_current

    cp "$new_jar" "$DEPLOY_DIR/ownclaw.jar"
    log "Deployed new JAR"

    # Sync skills and config from repo
    rsync -a --delete "$REPO_DIR/skills/core/" "$DEPLOY_DIR/skills/core/"
    rsync -a "$REPO_DIR/skills/manifest.json" "$DEPLOY_DIR/skills/manifest.json"
    log "Synced skills and manifest"

    # Restart service (only if systemd is running — skip in setup phase)
    if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        log "Restarting $SERVICE_NAME..."
        sudo systemctl restart "$SERVICE_NAME"
        wait_for_health
    elif systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
        log "Service installed but not running — skipping restart."
        log "Start manually: sudo systemctl start $SERVICE_NAME"
    else
        log "Systemd service not installed — skipping restart."
    fi
}

# === Health check after restart ===
wait_for_health() {
    log "Waiting for health check ($HEALTH_URL)..."
    local elapsed=0
    while [ "$elapsed" -lt "$HEALTH_TIMEOUT" ]; do
        if curl -sf "$HEALTH_URL" -o /dev/null 2>/dev/null; then
            log "Health check passed after ${elapsed}s"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    log "WARN: Health check did not pass within ${HEALTH_TIMEOUT}s"
    log "Check logs: sudo journalctl -u $SERVICE_NAME --since '5 min ago'"
    return 1
}

# === Rollback to previous JAR ===
rollback() {
    local latest_backup
    latest_backup=$(ls -1t "$BACKUP_DIR"/ownclaw-*.jar 2>/dev/null | head -1)
    if [ -z "$latest_backup" ]; then
        die "No backup available for rollback"
    fi

    log "Rolling back to: $(basename "$latest_backup")"
    cp "$latest_backup" "$DEPLOY_DIR/ownclaw.jar"
    sudo systemctl restart "$SERVICE_NAME"
    wait_for_health || die "Rollback also failed — manual intervention needed"
    log "Rollback successful"
}

# === Main ===
main() {
    local mode="${1:-deploy}"

    case "$mode" in
        --setup)
            do_setup
            exit 0
            ;;
        --update)
            log "--- Auto-update check ---"
            if pull_latest; then
                local jar
                jar=$(build_jar)
                deploy_jar "$jar" || { log "Deploy failed, attempting rollback"; rollback; }
                log "--- Update complete ---"
            else
                log "Already up to date"
            fi
            ;;
        --rollback)
            rollback
            ;;
        *)
            # Full deploy (no change check)
            log "=== Full Deploy ==="
            pull_latest || true
            local jar
            jar=$(build_jar)
            deploy_jar "$jar" || { log "Deploy failed, attempting rollback"; rollback; }
            log "=== Deploy Complete ==="
            ;;
    esac
}

main "$@"
