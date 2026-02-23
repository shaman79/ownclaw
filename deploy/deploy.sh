#!/usr/bin/env bash
# =============================================================================
# OwnClaw Deploy / Auto-Update Script
# =============================================================================
# Usage:
#   ./deploy.sh              # Full deploy (first time or force)
#   ./deploy.sh --update     # Only deploy if there are new commits (for cron)
#   ./deploy.sh --setup      # First-time server setup (run once, as root)
#
# Cron example (check for updates every 15 minutes):
#   */15 * * * * /opt/ownclaw/deploy/deploy.sh --update >> /opt/ownclaw/logs/deploy.log 2>&1
# =============================================================================
set -euo pipefail

# === Configuration ===
DEPLOY_DIR="/opt/ownclaw"
REPO_URL="https://github.com/shaman79/ownclaw.git"
REPO_DIR="${DEPLOY_DIR}/repo"
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
log()  { echo "[$(timestamp)] $*"; }
die()  { log "ERROR: $*" >&2; exit 1; }

# === First-time server setup (run as root) ===
do_setup() {
    log "=== OwnClaw Server Setup ==="

    if [ "$(id -u)" -ne 0 ]; then
        die "Setup must be run as root: sudo ./deploy.sh --setup"
    fi

    # Create service user
    if ! id -u ownclaw &>/dev/null; then
        log "Creating ownclaw user..."
        useradd --system --home-dir "$DEPLOY_DIR" --shell /usr/sbin/nologin ownclaw
    fi

    # Create directory structure
    log "Creating directories..."
    mkdir -p "$DEPLOY_DIR"/{data,logs,backups,skills/_envs}
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

    # Clone repo
    if [ ! -d "$REPO_DIR/.git" ]; then
        log "Cloning repository..."
        git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
    fi

    # Create .env template if it doesn't exist
    if [ ! -f "$DEPLOY_DIR/.env" ]; then
        log "Creating .env template..."
        cat > "$DEPLOY_DIR/.env" <<'ENVEOF'
# OwnClaw Production Environment
# Fill in your secrets and adjust settings here.
OPENAI_API_KEY=
TELEGRAM_BOT_TOKEN=
# OWNCLAW_PORT=8080
ENVEOF
        chmod 600 "$DEPLOY_DIR/.env"
    fi

    # Install systemd service
    log "Installing systemd service..."
    cp "$REPO_DIR/deploy/ownclaw.service" /etc/systemd/system/ownclaw.service
    systemctl daemon-reload
    systemctl enable ownclaw

    # Fix ownership
    chown -R ownclaw:ownclaw "$DEPLOY_DIR"

    # Initial build and deploy
    log "Running initial build..."
    su -s /bin/bash ownclaw -c "$REPO_DIR/deploy/deploy.sh"

    log ""
    log "=== Setup Complete ==="
    log "1. Edit secrets:   sudo nano $DEPLOY_DIR/.env"
    log "2. Start service:  sudo systemctl start ownclaw"
    log "3. Check status:   sudo systemctl status ownclaw"
    log "4. View logs:      sudo journalctl -u ownclaw -f"
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
    chmod +x gradlew 2>/dev/null || true
    ./gradlew build -x test --no-daemon --quiet

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
