#!/usr/bin/env bash
# =============================================================================
# OwnClaw Deploy / Auto-Update Script
# =============================================================================
# Usage:
#   ./deploy.sh              # Full deploy (first time or force)
#   ./deploy.sh --update     # Only deploy if there are new commits (for cron)
#                            #   As root it also installs whichever REQUIRED_PACKAGES are missing
#                            #   (bubblewrap among them) and restarts onto the skill jail.
#   ./deploy.sh --setup      # First-time server setup (run once, as root)
#                            #   Installs: JDK 21, Python 3 + venv, bubblewrap, Node.js 20, Docker/Podman, git,
#                            #   systemd service, sudoers rule, builds JAR, pre-provisions skill venvs + MCP servers.
#   ./deploy.sh --harden     # Remove the service user's path to root (run once, as root):
#                            #   root-owns the repo, replaces the ownclaw crontab with a
#                            #   root-owned systemd timer, deletes the sudoers grants.
#   ./deploy.sh --harden-status    # Is this host hardened? Read-only, no root needed.
#                            #   Exit 0 = hardened, 1 = the service user can reach root.
#   ./deploy.sh --install-sudoers  # Legacy: only needed on a host that is NOT hardened
#   ./deploy.sh --install-ollama   # Install Ollama and pull the default model (qwen2.5:14b)
#   ./deploy.sh --rollback   # Restore previous JAR
#   ./deploy.sh --reset      # Reset workspace to defaults (preserves .env, API keys, ollama config)
#   ./deploy.sh --test-cron  # Diagnose cron environment (check PATH, git, token, perms)
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

# === PATH + HOME setup for cron ===
# Cron runs with a minimal PATH (/usr/bin:/bin). We need git, curl, java, etc.
# Prepend standard locations so the script works identically from cron and interactive shells.
export PATH="${DEPLOY_DIR}/jdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"
export JAVA_HOME="${DEPLOY_DIR}/jdk"
# Some cron implementations don't set HOME; git and gradle may need it.
export HOME="${HOME:-${DEPLOY_DIR}}"

# Load .env if present and readable (picks up GITHUB_TOKEN and other vars). An admin who may not
# read it -- --harden-status needs no root -- goes on without it.
if [ -r "$DEPLOY_DIR/.env" ]; then
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
BUSY_URL="http://localhost:8080/api/health/busy"
# A first start after a build is slower than the next (cold caches, the build's JVM still
# exiting); one that needs more than this is rolled back.
HEALTH_TIMEOUT=120
BUSY_WAIT_TIMEOUT=300  # max 5 minutes to wait for task to finish
RESTART_PENDING_MARKER="${DEPLOY_DIR}/.restart-pending"
SUDOERS_FILE="/etc/sudoers.d/ownclaw-ownclaw-restart"
UPDATE_UNIT="/etc/systemd/system/ownclaw-update.service"
UPDATE_TIMER="/etc/systemd/system/ownclaw-update.timer"
# Commit the installed JAR was built from. --update compares origin against this rather
# than against the repo checkout: the checkout moves to the new commit *before* the build,
# so a failed or interrupted build used to leave it "up to date" with the old JAR still
# running, and nothing ever retried.
DEPLOYED_COMMIT_FILE="${DEPLOY_DIR}/.deployed-commit"
DEPLOY_ATTEMPTS_FILE="${DEPLOY_DIR}/.deploy-attempts"  # "<commit> <attempts>"
MAX_DEPLOY_ATTEMPTS=3  # --update stops retrying a commit after this many failed tries
# Distribution packages the service needs while it runs. --setup installs them, and every update
# run as root installs whichever is missing, so a package added here reaches the server by itself.
# bubblewrap is the skill jail (SkillJail): without it, skills run unjailed.
REQUIRED_PACKAGES="git python3 python3-venv python3-pip bubblewrap"
PACKAGES_RETRY_FILE="${DEPLOY_DIR}/.packages-retry-after"  # epoch: a failed install waits until then
PACKAGES_RETRY_SECONDS=21600                                # 6 h
JAIL_RESTART_MARKER="${DEPLOY_DIR}/.restart-for-jail"      # the service start bubblewrap was installed under

# === Helpers ===
timestamp() { date '+%Y-%m-%d %H:%M:%S'; }
log()  { echo "[$(timestamp)] $*" >&2; }
die()  { log "ERROR: $*"; exit 1; }

can_sudo_non_interactive() {
    command -v sudo &>/dev/null && sudo -n true &>/dev/null
}

# Hardened by --harden: the root-owned update timer, or the repo taken from the service user.
is_hardened_host() {
    [ -f "$UPDATE_UNIT" ] || [ "$(stat -c %U "$REPO_DIR" 2>/dev/null)" = root ]
}

# The restart grants only where they are wanted. On a hardened host none: its root timer needs
# none, and updates before this one put back what --harden removed -- taken away again here.
sudoers_for_this_host() {
    if is_hardened_host; then
        if [ -f "$SUDOERS_FILE" ] && [ "$(id -u)" -eq 0 ]; then
            rm -f "$SUDOERS_FILE"
            log "Removed $SUDOERS_FILE: a hardened host's updates run as root and need no grant"
        fi
        return 0
    fi
    ensure_sudoers_restart_rule || true
}

ensure_sudoers_restart_rule() {
    # Installs a minimal sudoers rule that allows the service user to restart OwnClaw
    # without a password. This is required for cron-based --update runs.
    local sudoers_file="/etc/sudoers.d/ownclaw-ownclaw-restart"
    local rule_restart="ownclaw ALL=(root) NOPASSWD: /usr/bin/systemctl restart ${SERVICE_NAME}"
    local rule_stop="ownclaw ALL=(root) NOPASSWD: /usr/bin/systemctl stop ${SERVICE_NAME}"
    local rule_chown="ownclaw ALL=(root) NOPASSWD: /usr/bin/chown -R ownclaw\\:ownclaw ${REPO_DIR}"
    local rule_cp_svc="ownclaw ALL=(root) NOPASSWD: /usr/bin/cp ${REPO_DIR}/deploy/ownclaw.service /etc/systemd/system/ownclaw.service"
    local rule_reload="ownclaw ALL=(root) NOPASSWD: /usr/bin/systemctl daemon-reload"
    local rule_linger="ownclaw ALL=(root) NOPASSWD: /usr/bin/loginctl enable-linger ownclaw"

    # Fast path: already present.
    if [ -f "$sudoers_file" ] && grep -Fqx "$rule_restart" "$sudoers_file" 2>/dev/null \
                               && grep -Fqx "$rule_stop"    "$sudoers_file" 2>/dev/null \
                               && grep -Fqx "$rule_chown"   "$sudoers_file" 2>/dev/null \
                               && grep -Fqx "$rule_cp_svc"  "$sudoers_file" 2>/dev/null \
                               && grep -Fqx "$rule_reload"  "$sudoers_file" 2>/dev/null \
                               && grep -Fqx "$rule_linger"  "$sudoers_file" 2>/dev/null; then
        return 0
    fi

    local tmp
    tmp=$(mktemp /tmp/ownclaw-sudoers-XXXXXX)
    printf '%s\n%s\n%s\n%s\n%s\n%s\n' "$rule_restart" "$rule_stop" "$rule_chown" "$rule_cp_svc" "$rule_reload" "$rule_linger" >"$tmp"

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

# Sync the systemd service file from the repo to /etc/systemd/system/ if changed.
# Requires the sudoers rules for cp and daemon-reload.
sync_service_file() {
    local src="$REPO_DIR/deploy/ownclaw.service"
    local dst="/etc/systemd/system/ownclaw.service"

    if [ ! -f "$src" ]; then
        return 0
    fi

    # Skip if unchanged
    if diff -q "$src" "$dst" &>/dev/null; then
        return 0
    fi

    log "Syncing systemd service file (changed)..."

    if [ "$(id -u)" -eq 0 ]; then
        cp "$src" "$dst"
        systemctl daemon-reload
        log "Service file updated and daemon reloaded"
        return 0
    fi

    if command -v sudo &>/dev/null; then
        if sudo -n cp "$src" "$dst" 2>/dev/null && sudo -n systemctl daemon-reload 2>/dev/null; then
            log "Service file updated and daemon reloaded (via sudo)"
            return 0
        fi
    fi

    log "WARN: Service file changed but cannot update /etc/systemd/system/ (need sudo). "
    log "       Run: sudo cp $src $dst && sudo systemctl daemon-reload"
    return 1
}

# === Check if agent is busy (processing a task) ===
# Returns 0 if idle (safe to restart), 1 if busy (skip restart).
wait_for_idle() {
    # If the service isn't running, it's not busy
    if ! systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        return 0
    fi

    local status
    status=$(curl -sf "$BUSY_URL" 2>/dev/null || echo "unknown")

    if [ "$status" = "idle" ]; then
        return 0
    fi

    if [ "$status" != "busy" ]; then
        # Service might be starting up or /api/health/busy not available yet
        log "Could not determine busy status (got: $status) — proceeding with deploy"
        return 0
    fi

    # Agent is busy — wait for it to finish (up to BUSY_WAIT_TIMEOUT)
    log "Agent is busy — waiting for task to finish before deploying..."
    local elapsed=0
    while [ "$elapsed" -lt "$BUSY_WAIT_TIMEOUT" ]; do
        sleep 10
        elapsed=$((elapsed + 10))
        status=$(curl -sf "$BUSY_URL" 2>/dev/null || echo "unknown")
        if [ "$status" = "idle" ]; then
            log "Agent is now idle after ${elapsed}s — proceeding with deploy"
            return 0
        fi
        log "Still busy... (${elapsed}s / ${BUSY_WAIT_TIMEOUT}s)"
    done

    log "Agent still busy after ${BUSY_WAIT_TIMEOUT}s — skipping deploy to avoid interrupting task"
    return 1
}

restart_service() {
    # Sync systmed service file before restarting (picks up any hardening changes)
    sync_service_file

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

# === Required packages ===
package_installed() {
    dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q "ok installed"
}

# apt-get for a run nobody watches: no prompts, no service restarts of its own (needrestart), a
# changed config file kept as it is, and an install that waits for unattended-upgrades to release
# the dpkg lock instead of failing on it. What a failed call printed goes to the log, whole.
apt_unattended() {
    local out rc=0
    out=$(DEBIAN_FRONTEND=noninteractive NEEDRESTART_SUSPEND=1 apt-get -q -y \
            -o DPkg::Lock::Timeout=600 \
            -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold \
            "$@" 2>&1) || rc=$?
    [ "$rc" -eq 0 ] || log "  apt-get $* failed (exit $rc): $out"
    return "$rc"
}

# Install what REQUIRED_PACKAGES names and the host lacks. Root and apt only; anywhere else it says
# what to install. Never fails the run: a deploy goes on without a package, as it did before.
# bubblewrap installed under a running service leaves JAIL_RESTART_MARKER for restart_onto_jail.
ensure_packages() {
    local missing=() failed=() p
    for p in $REQUIRED_PACKAGES; do
        package_installed "$p" || missing+=("$p")
    done
    [ "${#missing[@]}" -eq 0 ] && return 0

    if [ "$(id -u)" -ne 0 ] || ! command -v apt-get >/dev/null 2>&1; then
        log "WARN: missing packages: ${missing[*]} -- install them as root (sudo apt-get install ${missing[*]});"
        log "      only an update that runs as root on a Debian-family host (--harden) installs them itself"
        return 0
    fi

    local now retry_after
    now=$(date +%s)
    retry_after=$(cat "$PACKAGES_RETRY_FILE" 2>/dev/null || true)
    case "$retry_after" in ''|*[!0-9]*) retry_after=0 ;; esac
    if [ "$now" -lt "$retry_after" ]; then
        log "Missing packages: ${missing[*]} -- the last install failed; next try after $(date -d "@$retry_after" '+%Y-%m-%d %H:%M')"
        return 0
    fi

    log "Installing missing packages: ${missing[*]}"
    apt_unattended update || log "  WARN: apt-get update failed -- installing from the package lists already here"
    for p in "${missing[@]}"; do
        # --no-remove: an install that would take another package out fails instead.
        if apt_unattended install --no-install-recommends --no-remove "$p" && package_installed "$p"; then
            log "  Installed $p"
            if [ "$p" = bubblewrap ] && systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
                write_state_file "$JAIL_RESTART_MARKER" "$(service_invocation)" \
                    || log "  WARN: could not write $JAIL_RESTART_MARKER -- restart $SERVICE_NAME to turn the jail on"
            fi
        else
            failed+=("$p")
        fi
    done
    if [ "${#failed[@]}" -gt 0 ]; then
        write_state_file "$PACKAGES_RETRY_FILE" "$((now + PACKAGES_RETRY_SECONDS))" || true
        log "WARN: could not install: ${failed[*]} -- next try in $((PACKAGES_RETRY_SECONDS / 3600)) h"
    else
        rm -f "$PACKAGES_RETRY_FILE"
    fi
    return 0
}

# A plain restart, without restart_service's install of the repo's unit file: the JAR running may be
# older than the checkout (a commit that never built), and must not start under a newer unit.
restart_unit_only() {
    if [ "$(id -u)" -eq 0 ]; then
        systemctl restart "$SERVICE_NAME"
        return
    fi
    command -v sudo >/dev/null 2>&1 && sudo -n systemctl restart "$SERVICE_NAME" 2>/dev/null
}

# Which start of the service is running: systemd's InvocationID, new at every start. Empty when
# systemd does not say (older than 232), or the service is not running.
service_invocation() {
    systemctl show -p InvocationID --value "$SERVICE_NAME" 2>/dev/null || true
}

# The jail state this start of the service began with, for the log: "ON|INACTIVE|REFUSING|OFF <line>",
# or nothing when its journal does not have it. The line is the one SkillJail logs on the main thread
# while the service starts, read from this start's own entries. Nothing is decided on it.
jail_state_now() {
    local pid inv line i re
    local -a match
    pid=$(systemctl show -p MainPID --value "$SERVICE_NAME" 2>/dev/null || true)
    case "$pid" in ''|0|*[!0-9]*) return 0 ;; esac
    inv=$(service_invocation)
    if [ -n "$inv" ]; then match=("_SYSTEMD_INVOCATION_ID=$inv" "_PID=$pid")
    else match=("_SYSTEMD_UNIT=${SERVICE_NAME}.service" "_PID=$pid"); fi
    re="^[^ ]+ +(INFO|WARN|ERROR) +${pid} --- (\[[^]]*\] )*\[ *main\] com\.ownclaw\.sandbox\.SkillJail +: Skill isolation (ON|INACTIVE|REFUSING|is off)"
    for i in 1 2 3 4 5 6 7 8 9 10; do
        line=$(journalctl --no-pager -b -o cat "${match[@]}" 2>/dev/null | grep -E -m 1 "$re" || true)
        if [[ "$line" =~ $re ]]; then
            case "${BASH_REMATCH[3]}" in
                ON)       echo "ON $line" ;;
                INACTIVE) echo "INACTIVE $line" ;;
                REFUSING) echo "REFUSING $line" ;;
                *)        echo "OFF $line" ;;
            esac
            return 0
        fi
        sleep 1
    done
}

# bubblewrap was installed under a running service. In auto mode the jail turns on only in a process
# that has run no skill unjailed (SkillJail.RESTART_TO_JAIL), so the service needs one start since the
# install: the deploy's own restart when this cycle had one, otherwise a restart here, once idle. Then
# the jail's state is logged. bubblewrap stays either way: a jail that refuses is SkillJail's decision
# (it never runs skills around a jail that fails its self-check), and not the updater's to override.
restart_onto_jail() {
    [ -f "$JAIL_RESTART_MARKER" ] || return 0
    if ! package_installed bubblewrap || ! systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        rm -f "$JAIL_RESTART_MARKER"  # nothing to restart onto, or its next start is onto it anyway
        return 0
    fi
    # The staged JAR's restart comes first, with its own health check and rollback.
    if [ -f "$RESTART_PENDING_MARKER" ]; then
        log "bubblewrap is installed; the restart onto the jail waits until the staged JAR has started"
        return 0
    fi
    local installed_under before after state
    installed_under=$(cat "$JAIL_RESTART_MARKER" 2>/dev/null || true)
    before=$(service_invocation)
    if [ -z "$installed_under" ] || [ -z "$before" ] || [ "$before" = "$installed_under" ]; then
        if ! wait_for_idle; then
            log "bubblewrap is installed; the restart onto the jail waits for the next cycle"
            return 0
        fi
        log "Restarting $SERVICE_NAME to turn the skill jail on (bubblewrap installed)..."
        restart_unit_only || true
        after=$(service_invocation)
        if [ -n "$before" ] && [ "$after" = "$before" ]; then
            if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
                log "WARN: $SERVICE_NAME did not restart -- trying again next cycle"
            else
                log "ERROR: $SERVICE_NAME stopped and did not start again: systemctl status $SERVICE_NAME"
            fi
            return 0
        fi
    fi
    rm -f "$JAIL_RESTART_MARKER"
    if ! wait_for_health; then
        log "ERROR: $SERVICE_NAME is not healthy after its restart onto the jail. bubblewrap does not stop a"
        log "       start (SkillJail only logs and refuses), so the cause is elsewhere: journalctl -u $SERVICE_NAME"
        return 0
    fi
    state=$(jail_state_now)
    case "$state" in
        ON*)       log "Skill jail is ON: ${state#ON }" ;;
        INACTIVE*) log "WARN: bubblewrap is installed but cannot start a jail here, so skills still run unjailed: ${state#INACTIVE }" ;;
        REFUSING*) log "ERROR: the skill jail refuses every skill launch until its self-check passes (it looks again each minute;"
                   log "       /api/ops/health says why): ${state#REFUSING }" ;;
        OFF*)      log "Skill isolation is off in .env (OWNCLAW_SANDBOX_ISOLATION=off); bubblewrap stays installed for when it is on" ;;
        *)         log "WARN: the service is healthy, but its journal does not say whether the jail is on -- check /status" ;;
    esac
    return 0
}

# === Configure rootless Podman for the ownclaw user ===
# Rootless Podman needs subuid/subgid entries for user namespace mapping.
# Without these, 'podman build' fails with: lchown /etc/gshadow: invalid argument
setup_podman_rootless() {
    local uid
    uid=$(id -u ownclaw 2>/dev/null) || return 0

    # Ensure subuid entry exists for ownclaw
    if ! grep -q "^ownclaw:" /etc/subuid 2>/dev/null; then
        log "  Configuring subuid for ownclaw user (needed for rootless Podman)..."
        usermod --add-subuids 100000-165535 ownclaw 2>/dev/null || \
            echo "ownclaw:100000:65536" >> /etc/subuid
    fi

    # Ensure subgid entry exists for ownclaw
    if ! grep -q "^ownclaw:" /etc/subgid 2>/dev/null; then
        log "  Configuring subgid for ownclaw user (needed for rootless Podman)..."
        usermod --add-subgids 100000-165535 ownclaw 2>/dev/null || \
            echo "ownclaw:100000:65536" >> /etc/subgid
    fi

    # Apply the new namespace mapping
    podman system migrate 2>/dev/null || true

    # Enable lingering so the ownclaw user gets a persistent systemd user session.
    # Without this, rootless podman can't manage cgroups properly and 'apt-get install'
    # inside container builds fails with: sd-bus call: Interactive authentication required
    if command -v loginctl &>/dev/null; then
        loginctl enable-linger ownclaw 2>/dev/null || true
        log "  Enabled lingering for ownclaw user (required for rootless Podman cgroups)"
    fi

    log "  Podman rootless namespace mapping configured for ownclaw"
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

    # The rest of what the service needs from the distribution (bubblewrap among them); every
    # update keeps the same list installed.
    ensure_packages

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

    # ── Optional features wizard ───────────────────────────────────────────
    # Collect all choices upfront, then execute. Defaults are [Y] for the
    # recommended options so the user can press Enter through everything.
    echo ""
    echo "  ┌─────────────────────────────────────────┐"
    echo "  │        Optional Features                │"
    echo "  └─────────────────────────────────────────┘"
    echo ""

    local _opt_ollama="Y" _opt_container="Y" _opt_sudoers="Y" _opt_cron="Y" _opt_mcp="Y"

    read -r -p "  Install Ollama + pull default model (qwen2.5:14b)?     [Y/n]: " _ans
    [[ "$_ans" =~ ^[Nn] ]] && _opt_ollama="N"

    read -r -p "  Install container runtime (Podman/Docker) for sandbox? [Y/n]: " _ans
    [[ "$_ans" =~ ^[Nn] ]] && _opt_container="N"

    read -r -p "  Install sudoers rule (passwordless service restart)?   [Y/n]: " _ans
    [[ "$_ans" =~ ^[Nn] ]] && _opt_sudoers="N"

    read -r -p "  Install cron job (auto-update every 15 min)?           [Y/n]: " _ans
    [[ "$_ans" =~ ^[Nn] ]] && _opt_cron="N"

    read -r -p "  Pre-install MCP server packages + Chromium?            [Y/n]: " _ans
    [[ "$_ans" =~ ^[Nn] ]] && _opt_mcp="N"

    echo ""
    log "Selected: ollama=$_opt_ollama container=$_opt_container sudoers=$_opt_sudoers cron=$_opt_cron mcp=$_opt_mcp"

    # ── Install systemd service (always — required for the service to run) ──
    log "Installing systemd service..."
    cp "$REPO_DIR/deploy/ownclaw.service" /etc/systemd/system/ownclaw.service
    systemctl daemon-reload
    systemctl enable ownclaw

    # ── Sudoers rule ────────────────────────────────────────────────────────
    if [ "$_opt_sudoers" = "Y" ]; then
        ensure_sudoers_restart_rule || log "WARN: Failed to install sudoers rule — cron updates may require manual restart"
    else
        log "Skipping sudoers rule. Install later: sudo $REPO_DIR/deploy/deploy.sh --install-sudoers"
    fi

    # Fix ownership and permissions
    chown -R ownclaw:ownclaw "$DEPLOY_DIR"
    chmod +x "$REPO_DIR/deploy/deploy.sh"
    chmod +x "$REPO_DIR/gradlew"

    # ── Container runtime ──────────────────────────────────────────────────
    if [ "$_opt_container" = "Y" ]; then
        if ! command -v podman &>/dev/null && ! command -v docker &>/dev/null; then
            log "Installing container runtime (for sandboxed skill execution)..."
            if apt-cache show podman &>/dev/null 2>&1; then
                log "  Installing Podman..."
                apt-get update -qq && apt-get install -y -qq podman
                setup_podman_rootless
            else
                log "  Podman not available in repos, installing Docker..."
                curl -fsSL https://get.docker.com | sh >/dev/null 2>&1
                usermod -aG docker ownclaw 2>/dev/null || true
            fi
        else
            if command -v podman &>/dev/null; then
                log "Container runtime already installed: podman $(podman --version 2>/dev/null | head -1)"
                setup_podman_rootless
            else
                log "Container runtime already installed: $(docker --version 2>/dev/null | head -1)"
                usermod -aG docker ownclaw 2>/dev/null || true
            fi
        fi
    else
        log "Skipping container runtime. Skills will run as local processes (no sandbox)."
    fi

    # ── Ollama ─────────────────────────────────────────────────────────────
    if [ "$_opt_ollama" = "Y" ]; then
        install_ollama
    else
        log "Skipping Ollama. Install later: $REPO_DIR/deploy/deploy.sh --install-ollama"
    fi

    # ── MCP servers ────────────────────────────────────────────────────────
    if [ "$_opt_mcp" = "Y" ]; then
        provision_mcp_servers
    else
        log "Skipping MCP server pre-installation. Packages will be auto-downloaded by npx on first use."
    fi

    # ── Initial build and deploy ───────────────────────────────────────────
    log "Running initial build..."
    su -s /bin/bash ownclaw -c "$REPO_DIR/deploy/deploy.sh"

    # ── Auto-updates: root-owned timer, and lock the service user out of the repo ──
    if [ "$_opt_cron" = "Y" ]; then
        harden_host
    else
        log "Skipping auto-updates. Install them later with: sudo $REPO_DIR/deploy/deploy.sh --harden"
        log "  (that also removes the service user's path to root — see --harden)"
    fi

    log ""
    log "=== Setup Complete ==="
    log "1. Review secrets:  sudo nano $DEPLOY_DIR/.env"
    log "2. Start service:   sudo systemctl start ownclaw"
    log "3. Check status:    sudo systemctl status ownclaw"
    log "4. View logs:       sudo journalctl -u ownclaw -f"
    if [ "$_opt_cron" != "Y" ]; then
        log ""
        log "For auto-updates, add to crontab (sudo crontab -u ownclaw -e):"
        log "  */15 * * * * $REPO_DIR/deploy/deploy.sh --update >> $LOG_DIR/deploy.log 2>&1"
    fi
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

# === Install Ollama + default model ===
install_ollama() {
    local model="${OWNCLAW_EXECUTOR_MODEL:-qwen2.5:14b}"

    # Install Ollama if not present
    if command -v ollama &>/dev/null; then
        log "Ollama already installed: $(ollama --version 2>&1 | head -1)"
    else
        log "Installing Ollama..."
        curl -fsSL https://ollama.com/install.sh | sh || die "Failed to install Ollama"
        log "Ollama installed: $(ollama --version 2>&1 | head -1)"
    fi

    # Ensure the Ollama systemd service is enabled and running
    if systemctl list-unit-files ollama.service &>/dev/null 2>&1; then
        if ! systemctl is-active --quiet ollama 2>/dev/null; then
            log "Starting Ollama service..."
            systemctl enable ollama 2>/dev/null || true
            systemctl start ollama
            # Give it a moment to bind the API port
            sleep 3
        fi
    fi

    # Verify the API is responsive
    local ollama_url="${OWNCLAW_EXECUTOR_URL:-http://localhost:11434}"
    local retries=0
    while [ "$retries" -lt 10 ]; do
        if curl -sf "${ollama_url}/api/tags" -o /dev/null 2>/dev/null; then
            break
        fi
        retries=$((retries + 1))
        sleep 2
    done
    if [ "$retries" -ge 10 ]; then
        log "WARN: Ollama API not responding at $ollama_url — model pull may fail"
    fi

    # Pull the default model if not already present
    if ollama list 2>/dev/null | grep -q "^${model}"; then
        log "Model '$model' already available"
    else
        log "Pulling model '$model' (this may take a while)..."
        if ollama pull "$model"; then
            log "Model '$model' pulled successfully"
        else
            log "ERROR: Failed to pull model '$model'"
            log "  You can retry manually: ollama pull $model"
            return 1
        fi
    fi

    log "Ollama ready with model '$model'"
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

# === Repair file ownership in the repo (e.g. after accidental sudo operations) ===
repair_repo_perms() {
    local target_user
    target_user=$(stat -c '%U' "$REPO_DIR" 2>/dev/null || echo "ownclaw")

    # Find files NOT owned by the expected user
    local bad_files
    bad_files=$(find "$REPO_DIR" -not -user "$target_user" 2>/dev/null | head -5)
    [ -z "$bad_files" ] && return 0

    log "Repairing file ownership in $REPO_DIR..."
    if [ "$(id -u)" -eq 0 ]; then
        chown -R "$target_user":"$target_user" "$REPO_DIR"
        return 0
    fi
    if command -v sudo &>/dev/null && sudo -n chown -R "$target_user":"$target_user" "$REPO_DIR" 2>/dev/null; then
        return 0
    fi
    log "Cannot fix permissions (need root). Run: sudo chown -R $target_user:$target_user $REPO_DIR"
    return 1
}

# =============================================================================
# Host hardening
# =============================================================================
# The original arrangement gave the service user a path to root:
#
#   * /opt/ownclaw/repo (including deploy/ownclaw.service) was owned by ownclaw
#   * sudoers let ownclaw run, passwordless:
#       cp /opt/ownclaw/repo/deploy/ownclaw.service /etc/systemd/system/...
#       systemctl daemon-reload
#       systemctl restart ownclaw
#   * the unit ran with ReadWritePaths=/opt/ownclaw and NoNewPrivileges off
#
# So anything running as ownclaw - which means any skill the agent generates, and
# therefore anything a prompt-injected web page can reach - could write User=root
# into the unit file and have it installed and started. Even without the sudo
# rules, the 15-minute cron job ran OUTSIDE the service's mount namespace and
# copied that same file itself, so editing the unit and touching .restart-pending
# was enough.
#
# The fix inverts ownership: root owns the repo and the deploy script, the service
# user owns only its data, and the updater runs as a root-owned systemd timer
# instead of the service user's crontab. root needs no sudoers rules, so they go.
#
# This CANNOT be done by the cron updater itself: it runs as ownclaw, and the only
# way it could gain root is the escalation being removed. That is the property we
# want - an unprivileged service must not be able to rewrite its own privileges.
# So --update only detects and reports, and `sudo deploy.sh --harden` applies it.

# Is the dangerous arrangement still in place? Echoes the reasons, one per line.
hardening_issues() {
    local issues=""
    if [ -f "$SUDOERS_FILE" ]; then
        issues+="sudoers file $SUDOERS_FILE still grants the service user root for cp/daemon-reload\n"
    fi
    if [ -d "$REPO_DIR" ] && [ "$(stat -c '%U' "$REPO_DIR" 2>/dev/null)" != "root" ]; then
        issues+="$REPO_DIR is owned by $(stat -c '%U' "$REPO_DIR" 2>/dev/null) — the service user can edit the unit file and this script\n"
    fi
    if crontab -u ownclaw -l 2>/dev/null | grep -Fq "deploy.sh --update"; then
        issues+="the updater runs from the ownclaw crontab instead of a root-owned timer\n"
    fi
    printf '%b' "$issues"
}

# Called from --update (which runs unprivileged): report, never attempt.
warn_if_unhardened() {
    local issues
    issues=$(hardening_issues)
    [ -z "$issues" ] && return 0
    log "SECURITY: this host still allows the service user to reach root:"
    printf '%b' "$issues" | while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] && log "  - $line"
    done
    log "  Fix with one command, as root:  sudo $REPO_DIR/deploy/deploy.sh --harden"
    log "  (--update cannot fix this itself: it runs as ownclaw, and reaching root"
    log "   from here is exactly the escalation being removed.)"
}

# Apply the safe arrangement. Root only, idempotent, safe to re-run.
harden_host() {
    [ "$(id -u)" -eq 0 ] || die "--harden must run as root: sudo $REPO_DIR/deploy/deploy.sh --harden"

    log "=== Hardening host ==="
    local issues
    issues=$(hardening_issues)
    if [ -z "$issues" ]; then
        log "Already hardened — nothing to change."
    fi

    # Order matters. The replacement updater goes in FIRST and is verified, so that
    # a failure part-way through never leaves the host with no updater at all.

    # 1. Root-owned systemd timer to replace the user crontab.
    log "Installing root-owned update timer..."
    cat > "$UPDATE_UNIT" <<UNIT
[Unit]
Description=OwnClaw auto-update (pull, build, deploy)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
# Runs as root on purpose: it owns the repo and installs the unit file, so the
# service user never needs write access to either.
User=root
WorkingDirectory=${DEPLOY_DIR}
ExecStart=${REPO_DIR}/deploy/deploy.sh --update
StandardOutput=append:${LOG_DIR}/deploy.log
StandardError=append:${LOG_DIR}/deploy.log
UNIT

    cat > "$UPDATE_TIMER" <<TIMER
[Unit]
Description=Run the OwnClaw auto-update every 15 minutes

[Timer]
OnBootSec=5min
OnUnitActiveSec=15min
Unit=ownclaw-update.service

[Install]
WantedBy=timers.target
TIMER

    chown root:root "$UPDATE_UNIT" "$UPDATE_TIMER"
    chmod 0644 "$UPDATE_UNIT" "$UPDATE_TIMER"
    systemctl daemon-reload
    systemctl enable --now ownclaw-update.timer >/dev/null 2>&1 || true

    if systemctl is-enabled --quiet ownclaw-update.timer 2>/dev/null; then
        log "  Timer active: $(systemctl show -p NextElapseUSecRealtime --value ownclaw-update.timer 2>/dev/null || echo scheduled)"
    else
        die "Update timer did not enable — leaving the existing crontab in place. Check: systemctl status ownclaw-update.timer"
    fi

    # 2. Only now remove the old updater, so there is never a window with neither.
    if crontab -u ownclaw -l 2>/dev/null | grep -Fq "deploy.sh --update"; then
        crontab -u ownclaw -l 2>/dev/null | grep -Fv "deploy.sh --update" | crontab -u ownclaw - || true
        log "  Removed the deploy entry from the ownclaw crontab"
    fi

    # 3. Take the repo away from the service user. It keeps only its own data.
    log "Re-owning the repository to root..."
    chown -R root:root "$REPO_DIR"
    find "$REPO_DIR" -type d -exec chmod 755 {} + 2>/dev/null || true
    find "$REPO_DIR" -type f -exec chmod 644 {} + 2>/dev/null || true
    chmod 755 "$REPO_DIR/deploy/deploy.sh" "$REPO_DIR/gradlew" 2>/dev/null || true
    # Gradle needs a writable home; keep it outside the repo and owned by root.
    mkdir -p "$DEPLOY_DIR/.gradle" && chown -R root:root "$DEPLOY_DIR/.gradle"
    # The service user still owns everything it writes at runtime.
    for d in data skills logs backups; do
        [ -d "$DEPLOY_DIR/$d" ] && chown -R ownclaw:ownclaw "$DEPLOY_DIR/$d"
    done
    [ -f "$DEPLOY_DIR/.env" ] && { chown root:ownclaw "$DEPLOY_DIR/.env"; chmod 0640 "$DEPLOY_DIR/.env"; }
    [ -f "$DEPLOY_DIR/ownclaw.jar" ] && chown root:root "$DEPLOY_DIR/ownclaw.jar"
    log "  $REPO_DIR is now root-owned; data/, skills/, logs/ and backups/ stay with ownclaw"

    # 4. The sudo grants existed only so an unprivileged updater could install the
    #    unit and restart. The timer runs as root, so they are now pure liability.
    if [ -f "$SUDOERS_FILE" ]; then
        rm -f "$SUDOERS_FILE"
        log "  Removed $SUDOERS_FILE"
        if command -v visudo >/dev/null 2>&1 && ! visudo -c >/dev/null 2>&1; then
            log "  WARN: visudo reports a problem with the remaining sudoers files — check manually"
        fi
    fi

    # 5. Narrow the unit's writable paths and install it from the now-root-owned repo.
    sync_service_file || log "  WARN: could not sync the service file"
    systemctl daemon-reload

    log "=== Hardening complete ==="
    log "The service user can no longer edit the unit file, this script, or reach root."
    log "Auto-updates now run from ownclaw-update.timer (systemctl list-timers ownclaw-update)."
    log "Verify:  sudo -u ownclaw test -w $REPO_DIR/deploy/ownclaw.service && echo STILL WRITABLE || echo ok"
}

# === Deploy state ===
# Replace a small state file via rename. Atomic, and unlike '>' it also works when the
# existing file belongs to another user (e.g. left behind by a deploy run as root).
write_state_file() {
    local file="$1" content="$2"
    local tmp="${file}.tmp.$$"
    echo "$content" > "$tmp" && mv -f "$tmp" "$file"
}

# Commit the installed JAR was built from. Empty if unknown (JAR predates this record).
deployed_commit() {
    local commit=""
    if [ -f "$DEPLOYED_COMMIT_FILE" ]; then
        read -r commit _ < "$DEPLOYED_COMMIT_FILE" || true
    fi
    echo "$commit"
}

# Count an --update attempt for a commit; returns 1 once it has used up MAX_DEPLOY_ATTEMPTS.
# The attempt is recorded before the build starts, so a run that is killed half-way
# (OOM, reboot) counts too. Without the cap, a commit that cannot build would run
# Gradle on every cron cycle forever.
register_deploy_attempt() {
    local commit="$1" prev_commit="" attempts=0
    if [ -f "$DEPLOY_ATTEMPTS_FILE" ]; then
        read -r prev_commit attempts < "$DEPLOY_ATTEMPTS_FILE" || true
    fi
    if [ "$prev_commit" != "$commit" ] || ! [[ "$attempts" =~ ^[0-9]+$ ]]; then
        attempts=0
    fi

    if [ "$attempts" -ge "$MAX_DEPLOY_ATTEMPTS" ]; then
        log "ERROR: ${commit:0:8} failed to deploy $MAX_DEPLOY_ATTEMPTS times — not retrying automatically."
        log "  Find the build error earlier in this log, then push a fix or run: $REPO_DIR/deploy/deploy.sh"
        return 1
    fi

    attempts=$((attempts + 1))
    if ! write_state_file "$DEPLOY_ATTEMPTS_FILE" "$commit $attempts"; then
        log "ERROR: Cannot write $DEPLOY_ATTEMPTS_FILE — skipping auto-deploy rather than retrying unbounded"
        return 1
    fi
    log "Deploy attempt $attempts/$MAX_DEPLOY_ATTEMPTS for ${commit:0:8}"
}

# === Pull latest code, return 0 if there is a commit to deploy ===
pull_latest() {
    if [ ! -d "$REPO_DIR/.git" ]; then
        log "Cloning repository..."
        git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
        return 0
    fi

    cd "$REPO_DIR"

    # Ensure the remote URL uses current credentials
    git remote set-url origin "$REPO_URL" 2>/dev/null || true

    # Read current HEAD
    local before
    before=$(git rev-parse HEAD 2>/dev/null) || {
        log "ERROR: Cannot read current HEAD — repo may be corrupt"
        return 2
    }

    # Fetch latest from remote — explicitly check for failure.
    # NOTE: this function is called from 'if pull_latest;' which disables set -e,
    # so a failed git fetch would silently continue and the before/after comparison
    # would always show "no changes". We must check the exit code explicitly.
    local fetch_output
    if ! fetch_output=$(git fetch origin "$BRANCH" --depth 1 2>&1); then
        log "ERROR: git fetch failed — check network connectivity and GITHUB_TOKEN"
        [ -n "$fetch_output" ] && log "  git output: $fetch_output"
        return 2
    fi

    local after
    after=$(git rev-parse "origin/$BRANCH" 2>/dev/null) || {
        log "ERROR: Cannot read origin/$BRANCH after fetch"
        return 2
    }

    if [ "$before" != "$after" ]; then
        log "New commits: ${before:0:8} -> ${after:0:8}"

        # Try git reset; if it fails (e.g. permission denied), repair and retry
        if ! git reset --hard "origin/$BRANCH" --quiet 2>/dev/null; then
            log "git reset failed — attempting permission repair..."
            if repair_repo_perms; then
                git reset --hard "origin/$BRANCH" --quiet
            else
                die "Cannot update repo: permission denied. Run: sudo chown -R ownclaw:ownclaw $REPO_DIR"
            fi
        fi
    fi

    # Whether there is anything to deploy is decided by what is installed, not by the
    # checkout: the checkout already points at $after even if its build never succeeded.
    local deployed
    deployed=$(deployed_commit)
    if [ "$after" = "$deployed" ]; then
        return 1  # Installed JAR is already built from the latest commit
    fi

    if [ "$before" = "$after" ]; then
        if [ -z "$deployed" ]; then
            log "No record of which commit the installed JAR was built from — rebuilding ${after:0:8} once to establish it"
        else
            log "${after:0:8} is checked out but was never deployed (installed: ${deployed:0:8})"
        fi
    fi
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

    # If build/ is owned by root (from initial --setup), move it aside.
    # We own the parent dir so rename works even without write perms inside.
    if [ -d "$REPO_DIR/build" ] && ! [ -w "$REPO_DIR/build" ]; then
        log "Removing root-owned build directory..."
        mv "$REPO_DIR/build" "$REPO_DIR/build.stale.$$" 2>/dev/null || true
        rm -rf "$REPO_DIR/build.stale.$$" 2>/dev/null &  # background cleanup
    fi

    # Clean up stale Gradle compile transaction dirs from previous failed builds.
    # These may be root-owned (from --setup), so rm -rf can fail.
    # Use mv trick: ownclaw owns the parent dir, so rename always works.
    local compile_tmp="$REPO_DIR/build/tmp/compileJava"
    if [ -d "$compile_tmp" ]; then
        log "Cleaning stale compile temp dir..."
        mv "$compile_tmp" "$REPO_DIR/build/tmp/compileJava.stale.$$" 2>/dev/null || true
        rm -rf "$REPO_DIR/build/tmp/compileJava.stale.$$" 2>/dev/null &
    fi

    # Move aside build/classes — the running JVM can keep .class files locked
    # (memory-mapped or lazily loaded), which prevents Gradle from deleting them.
    if [ -d "$REPO_DIR/build/classes" ]; then
        log "Moving aside build/classes (may be locked by running JVM)..."
        mv "$REPO_DIR/build/classes" "$REPO_DIR/build/classes.stale.$$" 2>/dev/null || true
        rm -rf "$REPO_DIR/build/classes.stale.$$" 2>/dev/null &
    fi

    # Skip 'clean' — the running JVM may have files locked in build/.
    # Gradle's incremental build handles staleness correctly without clean.
    if ! ./gradlew build -x test --no-daemon >&2; then
        die "Gradle build failed"
    fi

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
    # A backup that did not complete must not be there for a rollback to pick as the newest.
    if ! cp "$current" "$BACKUP_DIR/ownclaw-${ts}.jar"; then
        rm -f "$BACKUP_DIR/ownclaw-${ts}.jar"
        return 1
    fi
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

    # A JAR still waiting for its restart never ran, so it is no build to roll back to: the
    # newest backup stays the last one that did. Nothing has been touched yet, so a failed
    # backup stops the deploy rather than rolling back.
    if [ -f "$RESTART_PENDING_MARKER" ]; then
        log "The JAR on disk was staged but never started -- keeping the last backup that ran"
    else
        backup_current || die "Could not back up the current JAR (disk full?) -- deploy not started"
    fi

    # IMPORTANT: do not overwrite the currently-running JAR in-place.
    # The JVM may still read classes/resources lazily from the file; truncating it mid-run
    # can cause runtime NoClassDefFoundError / ClassNotFoundException.
    local tmp_jar="$DEPLOY_DIR/ownclaw.jar.new"
    if ! cp "$new_jar" "$tmp_jar"; then
        rm -f "$tmp_jar"
        die "Could not copy the new JAR into place (disk full?) -- the running build is untouched"
    fi
    if ! mv -f "$tmp_jar" "$DEPLOY_DIR/ownclaw.jar"; then
        rm -f "$tmp_jar"
        die "Could not swap the new JAR into place -- the running build is untouched"
    fi
    log "Deployed new JAR"

    # Record it at the swap, not after the restart: from here on the JAR on disk is the
    # new build, and getting it loaded is the restart-pending marker's job.
    local commit
    commit=$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null || true)
    if [ -n "$commit" ] && write_state_file "$DEPLOYED_COMMIT_FILE" "$commit"; then
        rm -f "$DEPLOY_ATTEMPTS_FILE"
    else
        log "WARN: Could not record the deployed commit in $DEPLOYED_COMMIT_FILE"
    fi

    ensure_runtime_permissions

    # Ensure sudoers rule exists when possible (root deploys, or environments where sudo -n works).
    # If we cannot install it here, restart_service() will still fail with instructions.
    # Never on a hardened host: its root timer needs no grant, and --harden removed the grants
    # on purpose -- putting them back on every update undid it.
    sudoers_for_this_host

    # Restart service (only if systemd is running — skip in setup phase)
    if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        # Wait for any running task to finish before restarting
        if ! wait_for_idle; then
            log "Skipping restart — agent is busy. New JAR is staged; will retry on next cron cycle."
            touch "$RESTART_PENDING_MARKER"
            return 0
        fi

        log "Restarting $SERVICE_NAME..."
        if ! restart_service; then
            restart_instructions
            # The new JAR is installed but not loaded — let the next --update retry the
            # restart, otherwise the old build keeps running until someone notices.
            touch "$RESTART_PENDING_MARKER"
            die "Service restart failed (insufficient permissions)."
        fi
        rm -f "$RESTART_PENDING_MARKER"
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
    latest_backup=$(ls -1t "$BACKUP_DIR"/ownclaw-*.jar 2>/dev/null | head -1 || true)
    if [ -z "$latest_backup" ]; then
        die "No backup available for rollback"
    fi

    log "Rolling back to: $(basename "$latest_backup")"
    # Swapped in, never written over: the JVM still starting from the failed build reads its
    # classes from the file as it goes, and a JAR rewritten under it failed that start with
    # class-loading errors that looked like a fault in the build itself (2026-11-04).
    local tmp_jar="$DEPLOY_DIR/ownclaw.jar.new"
    if ! cp "$latest_backup" "$tmp_jar"; then
        rm -f "$tmp_jar"
        die "Rollback failed (could not copy $(basename "$latest_backup")) -- manual intervention needed"
    fi
    mv -f "$tmp_jar" "$DEPLOY_DIR/ownclaw.jar"

    # Keep the backed-out commit on record (flagged) so --update doesn't redeploy the
    # build we just rolled back from on every cycle. It moves on with the next commit.
    local commit
    commit=$(deployed_commit)
    if [ -n "$commit" ]; then
        write_state_file "$DEPLOYED_COMMIT_FILE" "$commit rolled-back" || true
        log "${commit:0:8} will not be redeployed automatically — push a fix, or run deploy.sh to force it"
    fi

    sudoers_for_this_host
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
        --harden)
            harden_host
            exit 0
            ;;
        --harden-status)
            # Read-only. --harden is idempotent, but "run it again and see what it says"
            # is a poor way to answer "is this host safe?", and it needs root. This
            # answers the question without changing anything and without privileges,
            # so it is also the thing to run before and after --harden to confirm.
            log "=== Hardening status ==="
            _issues=$(hardening_issues)
            if [ -z "$_issues" ]; then
                log "HARDENED — the service user has no path to root."
                log "  Checked: $SUDOERS_FILE absent, $REPO_DIR owned by root,"
                log "           no deploy entry in the ownclaw crontab."
                if systemctl is-enabled --quiet ownclaw-update.timer 2>/dev/null; then
                    log "  Updater: ownclaw-update.timer (root-owned) is enabled."
                else
                    log "  NOTE: ownclaw-update.timer is not enabled, so nothing is auto-updating."
                    log "        Re-run --harden to install it."
                fi
                exit 0
            fi
            log "NOT HARDENED — the service user can still reach root:"
            printf '%b' "$_issues" | while IFS= read -r line || [ -n "$line" ]; do
                [ -n "$line" ] && log "  - $line"
            done
            log "  Fix with: sudo $REPO_DIR/deploy/deploy.sh --harden"
            exit 1
            ;;
        --install-sudoers)
            if [ "$(id -u)" -ne 0 ]; then
                die "Must be run as root: sudo $REPO_DIR/deploy/deploy.sh --install-sudoers"
            fi
            # It hands the repo back to the service user and grants it root commands: on a
            # hardened host that is exactly what --harden took away, and the next update removes
            # the grant again anyway.
            if is_hardened_host; then
                die "This host is hardened (--harden): its updates run as root and need no sudoers rule. Not installing one."
            fi
            ensure_sudoers_restart_rule || die "Failed to install sudoers rule for service restart"
            # Also fix repo ownership while we have root access
            chown -R ownclaw:ownclaw "$REPO_DIR" 2>/dev/null || true
            chown -R ownclaw:ownclaw "$DEPLOY_DIR/skills" 2>/dev/null || true
            log "Sudoers rule installed + repo permissions fixed. Cron-based updates can restart the service without prompts."
            exit 0
            ;;
        --update)
            log "--- Auto-update check (user=$(whoami), home=$HOME) ---"
            warn_if_unhardened

            # If a previous deploy staged a new JAR but couldn't restart (agent was busy),
            # retry the restart now before checking for new code.
            if [ -f "$RESTART_PENDING_MARKER" ]; then
                log "Pending restart detected — retrying service restart..."
                if wait_for_idle; then
                    sync_service_file || true
                    if restart_service; then
                        rm -f "$RESTART_PENDING_MARKER"
                        if wait_for_health; then
                            log "Pending restart completed successfully"
                        else
                            log "The staged build did not come up -- rolling back"
                            rollback
                        fi
                    else
                        log "Pending restart failed (permissions?) — will retry next cycle"
                    fi
                else
                    log "Agent still busy — will retry restart on next cycle"
                fi
            fi

            local pull_rc=0
            pull_latest || pull_rc=$?
            # After the pull, so a package the new commit lists is in before its build starts;
            # whatever the pull's outcome, so a missing package never waits for a new commit.
            ensure_packages
            if [ "$pull_rc" -eq 0 ]; then
                if register_deploy_attempt "$(git -C "$REPO_DIR" rev-parse HEAD)"; then
                    local jar
                    jar=$(build_jar)
                    deploy_jar "$jar" || { log "Deploy failed, attempting rollback"; rollback; }
                    log "--- Update complete ---"
                fi
            elif [ "$pull_rc" -eq 1 ]; then
                if grep -q "rolled-back" "$DEPLOYED_COMMIT_FILE" 2>/dev/null; then
                    log "Latest commit was rolled back — waiting for a newer one (run deploy.sh to force it)"
                else
                    log "Already up to date"
                fi
            else
                log "--- Update check failed (rc=$pull_rc) — will retry next cycle ---"
            fi
            # Last, so a deploy's restart in this cycle is the restart onto the jail.
            restart_onto_jail
            ;;
        --test-cron)
            # Diagnostic mode: verifies the cron environment can run deployments
            log "=== Cron Environment Diagnostic ==="
            log "User: $(whoami) | UID: $(id -u) | HOME: $HOME"
            log "PATH: $PATH"
            log "JAVA_HOME: $JAVA_HOME"
            log "DEPLOY_DIR: $DEPLOY_DIR"
            log "REPO_DIR: $REPO_DIR"
            log "GITHUB_TOKEN: ${GITHUB_TOKEN:+set (${#GITHUB_TOKEN} chars)}${GITHUB_TOKEN:-NOT SET}"

            log ""
            log "--- Binary checks ---"
            for cmd in git java curl sudo; do
                if command -v "$cmd" &>/dev/null; then
                    log "  $cmd: $(command -v "$cmd")"
                else
                    log "  $cmd: NOT FOUND"
                fi
            done

            log ""
            log "--- Repository ---"
            if [ -d "$REPO_DIR/.git" ]; then
                cd "$REPO_DIR"
                log "  HEAD: $(git rev-parse HEAD 2>/dev/null || echo 'FAILED')"
                log "  Installed JAR built from: $(cat "$DEPLOYED_COMMIT_FILE" 2>/dev/null || echo 'unknown (next --update rebuilds once)')"
                log "  Failed deploy attempts: $(cat "$DEPLOY_ATTEMPTS_FILE" 2>/dev/null || echo 'none') (gives up at $MAX_DEPLOY_ATTEMPTS)"
                log "  Branch: $(git branch --show-current 2>/dev/null || echo 'UNKNOWN')"
                log "  Remote URL: $(git remote get-url origin 2>/dev/null | sed 's/x-access-token:[^@]*/x-access-token:***/' || echo 'FAILED')"
                log "  Fetch test:"
                local fetch_out
                if fetch_out=$(git fetch origin "$BRANCH" --depth 1 2>&1); then
                    log "    OK"
                else
                    log "    FAILED: $fetch_out"
                fi
            else
                log "  Repo not found at $REPO_DIR"
            fi

            log ""
            log "--- Service ---"
            log "  Active: $(systemctl is-active "$SERVICE_NAME" 2>/dev/null || echo 'unknown')"
            if [ -f "$RESTART_PENDING_MARKER" ]; then
                log "  Pending restart: YES (new JAR staged but service not yet restarted)"
            else
                log "  Pending restart: no"
            fi
            if sudo -n true 2>/dev/null; then
                log "  Passwordless sudo: YES"
            else
                log "  Passwordless sudo: NO (service restart from cron may fail)"
            fi

            log ""
            log "--- File permissions ---"
            log "  deploy.sh: $(ls -la "$REPO_DIR/deploy/deploy.sh" 2>/dev/null | awk '{print $1, $3, $4}')"
            log "  gradlew: $(ls -la "$REPO_DIR/gradlew" 2>/dev/null | awk '{print $1, $3, $4}')"
            log "  .env: $(ls -la "$DEPLOY_DIR/.env" 2>/dev/null | awk '{print $1, $3, $4}')"
            log "  ownclaw.jar: $(ls -la "$DEPLOY_DIR/ownclaw.jar" 2>/dev/null | awk '{print $1, $3, $4}')"
            log ""
            log "=== Diagnostic Complete ==="
            ;;
        --install-ollama)
            install_ollama
            exit 0
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
            ensure_packages
            local jar
            jar=$(build_jar)
            deploy_jar "$jar" || { log "Deploy failed, attempting rollback"; rollback; }
            restart_onto_jail
            log "=== Deploy Complete ==="
            ;;
    esac
}

main "$@"
