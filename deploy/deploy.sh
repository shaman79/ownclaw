#!/usr/bin/env bash
# =============================================================================
# OwnClaw Deploy / Auto-Update Script
# =============================================================================
# Usage:
#   ./deploy.sh              # Full deploy (first time or force)
#   ./deploy.sh --update     # Only deploy if there are new commits (for cron)
#   ./deploy.sh --setup      # First-time server setup (run once, as root)
#                            #   Installs: JDK 21, Python 3 + venv, Node.js 20, git, systemd service,
#                            #   sudoers rule, builds JAR, pre-provisions skill venvs + MCP servers.
#   ./deploy.sh --install-sudoers  # Install/repair sudoers rule (run once, as root)
#   ./deploy.sh --rollback   # Restore previous JAR
#   ./deploy.sh --reset      # Reset workspace to defaults (preserves .env, API keys, ollama config)
#
# Authentication:
#   During --setup, you will be prompted for your GitHub token interactively.
#   The token is saved to /opt/ownclaw/.env for subsequent cron-based updates.
#
# Cron example (check for updates every 15 minutes):
#   # Option A (recommended): run once to install sudoers rule (done automatically by --setup):
#   #   sudo /opt/ownclaw/repo/deploy/deploy.sh --install-sudoers
#   # Then run the update as ownclaw:
#   */15 * * * * /opt/ownclaw/repo/deploy/deploy.sh --update >> /opt/ownclaw/logs/deploy.log 2>&1
#
#   # Option B: run the update from root's crontab (script repairs ownership, but root deploys are riskier)
#   # */15 * * * * /opt/ownclaw/repo/deploy/deploy.sh --update >> /opt/ownclaw/logs/deploy.log 2>&1
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

can_sudo_non_interactive() {
    command -v sudo &>/dev/null && sudo -n true &>/dev/null
}

ensure_sudoers_restart_rule() {
    # Installs a minimal sudoers rule that allows the service user to restart OwnClaw
    # without a password. This is required for cron-based --update runs.
    local sudoers_file="/etc/sudoers.d/ownclaw-ownclaw-restart"
    local rule_restart="ownclaw ALL=(root) NOPASSWD: /usr/bin/systemctl restart ${SERVICE_NAME}"
    local rule_stop="ownclaw ALL=(root) NOPASSWD: /usr/bin/systemctl stop ${SERVICE_NAME}"

    # Fast path: already present.
    if [ -f "$sudoers_file" ] && grep -Fqx "$rule_restart" "$sudoers_file" 2>/dev/null \
                               && grep -Fqx "$rule_stop"    "$sudoers_file" 2>/dev/null; then
        return 0
    fi

    local tmp
    tmp=$(mktemp /tmp/ownclaw-sudoers-XXXXXX)
    printf '%s\n%s\n' "$rule_restart" "$rule_stop" >"$tmp"

    if [ "$(id -u)" -eq 0 ]; then
        install -o root -g root -m 0440 "$tmp" "$sudoers_file"
        rm -f "$tmp"
        # Best-effort validation (covers sudoers.d include)
        if command -v visudo &>/dev/null; then
            visudo -c &>/dev/null || die "sudoers validation failed after writing $sudoers_file"
        fi
        log "Installed sudoers rule: $sudoers_file"
        return 0
    fi

    # Non-root: we can only write sudoers if we already have non-interactive sudo.
    if can_sudo_non_interactive; then
        sudo -n install -o root -g root -m 0440 "$tmp" "$sudoers_file"
        rm -f "$tmp"
        if command -v visudo &>/dev/null; then
            sudo -n visudo -c &>/dev/null || die "sudoers validation failed after writing $sudoers_file"
        fi
        log "Installed sudoers rule via sudo: $sudoers_file"
        return 0
    fi

    rm -f "$tmp"
    return 1
}

restart_service() {
    # Never prompt for a password (cron has no TTY).
    if [ "$(id -u)" -eq 0 ]; then
        systemctl restart "$SERVICE_NAME"
        return 0
    fi

    # The sudoers rule grants only this specific command — don't gate on
    # can_sudo_non_interactive (which tests 'sudo -n true', a command the
    # narrow NOPASSWD rule does not cover).
    if command -v sudo &>/dev/null && sudo -n systemctl restart "$SERVICE_NAME" 2>/dev/null; then
        return 0
    fi

    return 1
}

stop_service() {
    if [ "$(id -u)" -eq 0 ]; then
        systemctl stop "$SERVICE_NAME"
        return 0
    fi

    if command -v sudo &>/dev/null && sudo -n systemctl stop "$SERVICE_NAME" 2>/dev/null; then
        return 0
    fi

    return 1
}

restart_instructions() {
    cat >&2 <<EOF

Cannot restart systemd service '$SERVICE_NAME'.

This deploy was run as user '$(id -un)' (uid=$(id -u)) and needs permission to restart the service.

Fix options:
  1) Install the sudoers rule (recommended):
      sudo $REPO_DIR/deploy/deploy.sh --install-sudoers

  2) Run the cron job as root instead.

After fixing, re-run:
  $REPO_DIR/deploy/deploy.sh --update

EOF
}

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

    # Allow ownclaw to read systemd journal (so shell_command can run journalctl for self-diagnosis)
    if getent group systemd-journal &>/dev/null; then
        usermod -aG systemd-journal ownclaw
        log "Added ownclaw to systemd-journal group"
    fi

    # Create directory structure
    log "Creating directories..."
    mkdir -p "$DEPLOY_DIR"/{data,logs,backups,skills/_envs,skills/generated}
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
        apt-get update -qq && apt-get install -y -qq python3 python3-venv python3-full python3-pip
    fi

    # Always ensure python3-venv / python3-full are installed, even when python3 was
    # pre-installed by the OS.  Without python3-venv the skill venv creation fails with
    # "ensurepip is not available" and the whole skill pipeline degrades.
    if ! python3 -c "import ensurepip" 2>/dev/null; then
        log "Installing python3-venv and python3-full (ensurepip missing)..."
        apt-get update -qq && apt-get install -y -qq python3-venv python3-full 2>/dev/null || true
    fi

    # Ensure pip is present (some minimal installs omit it)
    if ! python3 -m pip --version &>/dev/null; then
        log "Installing python3-pip..."
        apt-get update -qq && apt-get install -y -qq python3-pip
    fi

    # Install Node.js 20 LTS if missing (needed for MCP stdio servers)
    if ! command -v node &>/dev/null || ! node --version 2>/dev/null | grep -qE '^v(18|20|21|22|23|24)'; then
        log "Installing Node.js 20 LTS..."
        curl -fsSL https://deb.nodesource.com/setup_20.x | bash - >/dev/null 2>&1
        apt-get install -y -qq nodejs
    else
        log "Node.js already installed: $(node --version)"
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

    # Allow cron-based updates (running as ownclaw) to restart the service without password prompts.
    ensure_sudoers_restart_rule || die "Failed to install sudoers rule for service restart"

    # Fix ownership and permissions
    chown -R ownclaw:ownclaw "$DEPLOY_DIR"
    chmod +x "$REPO_DIR/deploy/deploy.sh"
    chmod +x "$REPO_DIR/gradlew"

    # Pre-provision MCP server npm packages
    provision_mcp_servers

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

# === Reset workspace to a clean default state ===
# Preserves: .env (API keys, tokens), system_settings connection/API config
# Clears:    DB (all tables), generated skills, skill virtualenvs
do_reset() {
    log "=== OwnClaw Workspace Reset ==="
    echo ""
    echo "  This will reset the workspace to a clean default state:"
    echo "    CLEAR  — all conversations, tasks, event log, plan cache"
    echo "    CLEAR  — all users (will need to re-register)"
    echo "    CLEAR  — all teaching log and user preferences"
    echo "    CLEAR  — all generated skills and their Python virtualenvs"
    echo ""
    echo "  Preserved:"
    echo "    KEEP   — .env  (OPENAI_API_KEY, TELEGRAM_BOT_TOKEN, OWNCLAW_EXECUTOR_*, etc.)"
    echo "    KEEP   — system_settings rows for connection/API config (URL, model, port, token)"
    echo "    BACKUP — database backed up to $DEPLOY_DIR/backups/ before deletion"
    echo ""
    read -r -p "  Type YES to confirm reset: " _reset_confirm
    if [ "$_reset_confirm" != "YES" ]; then
        log "Reset cancelled."
        exit 0
    fi
    echo ""

    local db_path="$DEPLOY_DIR/data/ownclaw.db"

    # ── Step 1: Stop the service ────────────────────────────────────────────
    if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        log "Stopping $SERVICE_NAME..."
        if ! stop_service; then
            die "Cannot stop service. Run as root or ensure the sudoers rule is installed "\
                "(sudo $REPO_DIR/deploy/deploy.sh --install-sudoers)."
        fi
    fi

    # ── Step 2: Preserve connection/API settings from system_settings ───────
    # These rows hold wizard-entered config that complements .env.
    # We preserve any key that looks like a URL, model, port, API key, or token.
    local _saved_settings=""
    if [ -f "$db_path" ] && command -v sqlite3 &>/dev/null; then
        log "Reading connection settings from system_settings..."
        _saved_settings=$(sqlite3 "$db_path" \
            "SELECT key, value FROM system_settings WHERE \
             key LIKE '%url%'   OR key LIKE '%model%'   OR key LIKE '%port%'  OR \
             key LIKE '%token%' OR key LIKE '%api_key%' OR key LIKE '%executor%' OR \
             key LIKE '%mentor%' OR key LIKE '%telegram%' OR key LIKE '%openai%' OR \
             key LIKE '%ollama%';" 2>/dev/null || true)
        if [ -n "$_saved_settings" ]; then
            local _n
            _n=$(echo "$_saved_settings" | grep -c '.' || true)
            log "Preserving $_n row(s) from system_settings"
        else
            log "No connection settings found in system_settings — nothing to preserve"
        fi
    elif [ -f "$db_path" ] && ! command -v sqlite3 &>/dev/null; then
        log "WARN: sqlite3 not installed — cannot preserve system_settings rows"
        log "      Install it with: sudo apt-get install -y sqlite3"
    fi

    # ── Step 3: Back up then delete the database ────────────────────────────
    if [ -f "$db_path" ]; then
        local _ts
        _ts=$(date '+%Y%m%d-%H%M%S')
        local _db_backup="$DEPLOY_DIR/backups/ownclaw-db-pre-reset-${_ts}.db"
        mkdir -p "$DEPLOY_DIR/backups"
        cp "$db_path" "$_db_backup"
        log "Database backed up → $(basename "$_db_backup")"
        rm -f "$db_path" "${db_path}-wal" "${db_path}-shm"
        log "Database deleted"
    else
        log "No database found — skipping DB step"
    fi

    # ── Step 4: Wipe generated skills ───────────────────────────────────────
    if [ -d "$DEPLOY_DIR/skills/generated" ]; then
        local _skill_dirs
        _skill_dirs=$(find "$DEPLOY_DIR/skills/generated" -mindepth 2 -maxdepth 2 -type d 2>/dev/null | wc -l)
        rm -rf "$DEPLOY_DIR/skills/generated"
        mkdir -p "$DEPLOY_DIR/skills/generated"
        log "Removed $_skill_dirs generated skill version(s)"
    fi

    # ── Step 5: Wipe skill Python virtualenvs ───────────────────────────────
    if [ -d "$DEPLOY_DIR/skills/_envs" ]; then
        local _env_dirs
        _env_dirs=$(find "$DEPLOY_DIR/skills/_envs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l)
        rm -rf "$DEPLOY_DIR/skills/_envs"
        mkdir -p "$DEPLOY_DIR/skills/_envs"
        log "Removed $_env_dirs skill virtualenv(s)"
    fi

    ensure_runtime_permissions

    # ── Step 7: Start service — Liquibase recreates the schema ───────────────
    local _service_started=false
    if systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
        log "Starting $SERVICE_NAME (Liquibase will recreate DB schema)..."
        if [ "$(id -u)" -eq 0 ]; then
            systemctl start "$SERVICE_NAME"
            _service_started=true
        elif can_sudo_non_interactive; then
            sudo -n systemctl start "$SERVICE_NAME"
            _service_started=true
        else
            log "Cannot start service automatically — run: sudo systemctl start $SERVICE_NAME"
            log "After starting, re-run this script with --reset-restore-settings if needed."
        fi
        if $_service_started; then
            wait_for_health || log "WARN: Service started but health check timed out"
        fi
    else
        log "Systemd service not installed — skipping start"
    fi

    # ── Step 8: Re-insert preserved system_settings ─────────────────────────
    if [ -n "$_saved_settings" ] && command -v sqlite3 &>/dev/null; then
        # Wait briefly for Liquibase to finish creating tables if service just started.
        if $_service_started; then
            sleep 2
        fi
        if [ -f "$db_path" ]; then
            log "Restoring preserved settings into fresh database..."
            local _restored=0
            while IFS='|' read -r _key _value; do
                [ -z "$_key" ] && continue
                # Escape single quotes in value for SQLite
                _value_escaped=$(printf '%s' "$_value" | sed "s/'/''/g")
                if sqlite3 "$db_path" \
                    "INSERT OR REPLACE INTO system_settings(key, value, updated_at) \
                     VALUES('$_key', '$_value_escaped', datetime('now'));" 2>/dev/null; then
                    _restored=$((_restored + 1))
                else
                    log "WARN: Could not restore setting '$_key'"
                fi
            done <<< "$_saved_settings"
            log "Restored $_restored preserved setting(s)"
        else
            log "WARN: DB not found after service start — preserved settings not restored"
            log "      Saved settings:"
            echo "$_saved_settings" | while IFS='|' read -r _k _v; do
                log "        $_k = $_v"
            done
        fi
    fi

    log ""
    log "=== Reset Complete ==="
    log "The workspace is clean. API keys and connection config were preserved."
    log "Users will need to re-register via the web UI or Telegram."
}

ensure_runtime_permissions() {
    # This deploy script is sometimes (accidentally) run as root.
    # Fix ownership so the service user can write generated skills and data.
    mkdir -p "$DEPLOY_DIR"/skills/{generated,_envs} "$DEPLOY_DIR"/data "$DEPLOY_DIR"/logs 2>/dev/null || true

    if [ "$(id -u)" -eq 0 ]; then
        chown -R ownclaw:ownclaw "$DEPLOY_DIR"/skills "$DEPLOY_DIR"/data "$DEPLOY_DIR"/logs 2>/dev/null || true
    fi

    chmod -R u+rwX,go-rwx "$DEPLOY_DIR"/skills/generated "$DEPLOY_DIR"/skills/_envs 2>/dev/null || true
}

# === Pre-provision MCP server npm packages ===
# Installs the four default MCP server packages globally so they start instantly
# without hitting the network on first tool call.  Also installs system Chromium
# for the Puppeteer server (PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser).
#
# Called from do_setup() as root.
provision_mcp_servers() {
    if ! command -v npm &>/dev/null; then
        log "WARN: npm not found — skipping MCP server pre-installation"
        return 0
    fi

    # Install system Chromium for the Puppeteer MCP server.
    # application.yaml sets PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true and points to this binary.
    if ! command -v chromium-browser &>/dev/null && ! command -v chromium &>/dev/null; then
        log "Installing Chromium (required by MCP Puppeteer server)..."
        apt-get update -qq && (
            apt-get install -y -qq chromium-browser 2>/dev/null ||
            apt-get install -y -qq chromium 2>/dev/null || true
        )
    else
        log "Chromium already installed"
    fi

    log "Pre-installing MCP server npm packages..."
    local failed=0
    local pkgs=(
        "@modelcontextprotocol/server-sqlite"
        "@modelcontextprotocol/server-brave-search"
        "@modelcontextprotocol/server-fetch"
        "@modelcontextprotocol/server-puppeteer"
    )
    for pkg in "${pkgs[@]}"; do
        log "  Installing $pkg ..."
        if PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true npm install -g --quiet "$pkg" 2>/dev/null; then
            log "  OK: $pkg"
        else
            log "  WARN: npm install failed for $pkg — will be auto-downloaded by npx on first use"
            failed=$((failed + 1))
        fi
    done

    log "MCP server provisioning: $((${#pkgs[@]} - failed)) installed, $failed failed"
    return 0
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

    # IMPORTANT: do not overwrite the currently-running JAR in-place.
    # The JVM may still read classes/resources lazily from the file; truncating it mid-run
    # can cause runtime NoClassDefFoundError / ClassNotFoundException.
    local tmp_jar="$DEPLOY_DIR/ownclaw.jar.new"
    cp "$new_jar" "$tmp_jar"
    mv -f "$tmp_jar" "$DEPLOY_DIR/ownclaw.jar"
    log "Deployed new JAR"

    ensure_runtime_permissions

    # Ensure sudoers rule exists when possible (root deploys, or environments where sudo -n works).
    # If we cannot install it here, restart_service() will still fail with instructions.
    ensure_sudoers_restart_rule || true

    # Restart service (only if systemd is running — skip in setup phase)
    if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        log "Restarting $SERVICE_NAME..."
        if ! restart_service; then
            restart_instructions
            die "Service restart failed (insufficient permissions)."
        fi
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

    ensure_sudoers_restart_rule || true
    if ! restart_service; then
        restart_instructions
        die "Rollback failed (could not restart service)."
    fi
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
        --install-sudoers)
            if [ "$(id -u)" -ne 0 ]; then
                die "Must be run as root: sudo $REPO_DIR/deploy/deploy.sh --install-sudoers"
            fi
            ensure_sudoers_restart_rule || die "Failed to install sudoers rule for service restart"
            log "Sudoers rule installed. Cron-based updates can restart the service without prompts."
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
        --reset)
            do_reset
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
