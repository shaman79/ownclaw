#!/usr/bin/env bash
# =============================================================================
# Ollama: detect how it was installed, then update it in the same way
# =============================================================================
# Written because the install method on a given box is often forgotten. This
# figures it out from evidence on disk rather than assuming, and refuses to
# guess: if it cannot tell, it says so and stops instead of installing a second
# copy alongside the first, which is the usual way these boxes end up with two
# Ollama binaries and a confusing PATH.
#
# Usage:
#   ./ollama-update.sh                 # detect, report, and update after confirming
#   ./ollama-update.sh --check         # detect and report only; changes nothing
#   ./ollama-update.sh --yes           # no prompt (for automation)
#   ./ollama-update.sh --rollback      # restore the binary backed up by the last run
#
# Run it ON THE OLLAMA HOST. Needs root for every method except Docker.
# =============================================================================
set -uo pipefail

BACKUP_DIR="/var/backups/ollama"
API="${OLLAMA_HOST:-http://127.0.0.1:11434}"
ASSUME_YES=0
MODE="update"

for arg in "$@"; do
    case "$arg" in
        --check)    MODE="check" ;;
        --rollback) MODE="rollback" ;;
        --yes|-y)   ASSUME_YES=1 ;;
        -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
    esac
done

log()  { printf '[ollama-update] %s\n' "$*"; }
die()  { printf '[ollama-update] ERROR: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

need_root() {
    [ "$(id -u)" -eq 0 ] || die "This method needs root. Re-run with sudo."
}

confirm() {
    [ "$ASSUME_YES" -eq 1 ] && return 0
    printf '[ollama-update] %s [y/N] ' "$1"
    read -r reply </dev/tty || return 1
    case "$reply" in [yY]|[yY][eE][sS]) return 0 ;; *) return 1 ;; esac
}

# ── Facts ────────────────────────────────────────────────────────────────────

installed_version() {
    if have ollama; then
        ollama --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
    fi
}

running_version() {
    curl -fsS -m 5 "$API/api/version" 2>/dev/null |
        grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}

latest_version() {
    curl -fsS -m 20 https://api.github.com/repos/ollama/ollama/releases/latest 2>/dev/null |
        grep -oE '"tag_name"[^,]*' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}

# Sets METHOD, BINARY, DETAIL. METHOD is one of:
#   docker | snap | deb | rpm | pacman | official | source | unknown
detect_method() {
    METHOD="unknown"; BINARY=""; DETAIL=""

    if have docker && docker ps --format '{{.Image}} {{.Names}}' 2>/dev/null | grep -qi 'ollama'; then
        METHOD="docker"
        DETAIL=$(docker ps --format '{{.Names}} ({{.Image}})' 2>/dev/null | grep -i ollama | head -1)
        return
    fi

    if have snap && snap list ollama >/dev/null 2>&1; then
        METHOD="snap"
        DETAIL=$(snap list ollama 2>/dev/null | awk 'NR==2 {print "channel " $4 ", rev " $3}')
        return
    fi

    have ollama || { DETAIL="no ollama binary on PATH"; return; }
    BINARY=$(readlink -f "$(command -v ollama)")

    if have dpkg && dpkg -S "$BINARY" >/dev/null 2>&1; then
        METHOD="deb";    DETAIL=$(dpkg -S "$BINARY" 2>/dev/null | cut -d: -f1); return
    fi
    if have rpm && rpm -qf "$BINARY" >/dev/null 2>&1; then
        METHOD="rpm";    DETAIL=$(rpm -qf "$BINARY" 2>/dev/null); return
    fi
    if have pacman && pacman -Qo "$BINARY" >/dev/null 2>&1; then
        METHOD="pacman"; DETAIL=$(pacman -Qo "$BINARY" 2>/dev/null); return
    fi

    # No package owns it. The official install.sh drops the binary in
    # /usr/local/bin and its bundled libraries in /usr/local/lib/ollama; a
    # source build usually has neither, and often sits next to a git checkout.
    if [ -d /usr/local/lib/ollama ]; then
        METHOD="official"; DETAIL="binary $BINARY + /usr/local/lib/ollama"; return
    fi
    if [ -d "$(dirname "$BINARY")/../.git" ] || [ -f "$(dirname "$BINARY")/../go.mod" ]; then
        METHOD="source"; DETAIL="looks built from a checkout near $BINARY"; return
    fi
    METHOD="source"; DETAIL="unpackaged binary at $BINARY, no /usr/local/lib/ollama"
}

# The unit that actually serves the API. A box often carries helpers next to it -
# ollama-warmup.service, ollama-models.service - and those sort BEFORE ollama.service
# alphabetically, so taking the first match picked the wrong one. Restarting a warmup
# unit is not merely useless: it usually preloads a model and blocks, which hangs the
# caller. Match the canonical name exactly and only fall back to a prefix search.
service_name() {
    local units
    units=$(systemctl list-unit-files --no-legend 2>/dev/null | grep -oE '^ollama[^ ]*\.service')
    if printf '%s\n' "$units" | grep -qx 'ollama.service'; then
        echo "ollama.service"; return
    fi
    printf '%s\n' "$units" | head -1
}

# Only touch a unit that is genuinely in use, and never wait on it forever.
maybe_restart() {
    local svc="$1"
    [ -n "$svc" ] || return 0
    if ! systemctl is-enabled --quiet "$svc" 2>/dev/null &&
       ! systemctl is-active  --quiet "$svc" 2>/dev/null; then
        log "Not restarting $svc — it is neither enabled nor running"
        return 0
    fi
    need_root
    log "Restarting $svc"
    timeout 120 systemctl restart "$svc" ||
        log "WARN: restarting $svc did not finish in 120s — check: systemctl status $svc"
}

# ── Actions ──────────────────────────────────────────────────────────────────

backup_binary() {
    [ -n "$BINARY" ] || return 0
    need_root
    mkdir -p "$BACKUP_DIR"
    local dest="$BACKUP_DIR/ollama-$(installed_version)-$(date +%Y%m%d-%H%M%S)"
    cp -a "$BINARY" "$dest" && ln -sfn "$dest" "$BACKUP_DIR/latest"
    log "Backed up the current binary to $dest"
}

do_update() {
    local svc; svc=$(service_name)
    case "$METHOD" in
        docker)
            local name image
            name=$(docker ps --format '{{.Names}} {{.Image}}' | grep -i ollama | head -1 | awk '{print $1}')
            image=$(docker ps --format '{{.Names}} {{.Image}}' | grep -i ollama | head -1 | awk '{print $2}')
            log "docker pull $image && recreate $name"
            docker pull "$image" || die "docker pull failed"
            log "Pulled. Recreate the container yourself so its flags, volumes and GPU"
            log "options are preserved — this script will not guess them:"
            log "    docker stop $name && docker rm $name && <your original docker run ...>"
            log "If it is managed by compose:  docker compose pull && docker compose up -d"
            return 0
            ;;
        snap)     need_root; log "snap refresh ollama"; snap refresh ollama || die "snap refresh failed" ;;
        deb)      need_root; log "apt-get update && apt-get install --only-upgrade $DETAIL"
                  apt-get update -qq && apt-get install -y --only-upgrade "$DETAIL" || die "apt upgrade failed" ;;
        rpm)      need_root; log "dnf upgrade -y ollama"; (have dnf && dnf upgrade -y ollama) ||
                  (have yum && yum update -y ollama) || die "rpm upgrade failed" ;;
        pacman)   need_root; log "pacman -Syu ollama"; pacman -Syu --noconfirm ollama || die "pacman upgrade failed" ;;
        official)
            need_root
            backup_binary
            log "Re-running the official installer (it upgrades in place and keeps the unit)"
            curl -fsSL https://ollama.com/install.sh | sh || die "official installer failed"
            # The installer enables and starts ollama.service itself, so there is
            # nothing left to restart and a second restart is just another outage.
            return 0
            ;;
        source)
            log "This binary was built from source, not installed by a package manager."
            log "Updating it means rebuilding, which this script will not do behind your back."
            log "Either rebuild from your checkout:"
            log "    git -C <checkout> pull && go build . && sudo install -m755 ollama $BINARY"
            log "or switch to the official installer, which will replace $BINARY:"
            log "    curl -fsSL https://ollama.com/install.sh | sh"
            return 1
            ;;
        *)  die "Could not determine how Ollama was installed. Nothing changed. Evidence: ${DETAIL:-none}" ;;
    esac

    maybe_restart "$svc"
    return 0
}

do_rollback() {
    need_root
    [ -L "$BACKUP_DIR/latest" ] || die "No backup found in $BACKUP_DIR"
    local target; target=$(readlink -f "$BACKUP_DIR/latest")
    [ -n "$BINARY" ] || die "Cannot locate the current ollama binary to replace"
    log "Restoring $target -> $BINARY"
    install -m 0755 "$target" "$BINARY" || die "restore failed"
    maybe_restart "$(service_name)"
    log "Rolled back to $(installed_version)"
}

# ── Main ─────────────────────────────────────────────────────────────────────

detect_method
INSTALLED=$(installed_version)
RUNNING=$(running_version)
LATEST=$(latest_version)

log "Install method : $METHOD${DETAIL:+  ($DETAIL)}"
log "Binary         : ${BINARY:-n/a}"
SVC=$(service_name); log "Service        : ${SVC:-none}"
log "Version on disk: ${INSTALLED:-unknown}"
log "Version serving: ${RUNNING:-not responding on $API}"
log "Latest upstream: ${LATEST:-could not check}"

if [ -n "$INSTALLED" ] && [ -n "$RUNNING" ] && [ "$INSTALLED" != "$RUNNING" ]; then
    log "NOTE: the running server ($RUNNING) is older than the binary on disk ($INSTALLED) —"
    log "      it needs a restart, or a second copy is running from a different path."
fi

case "$MODE" in
    check)    log "--check: nothing changed."; exit 0 ;;
    rollback) do_rollback; exit $? ;;
esac

if [ -n "$LATEST" ] && [ "$INSTALLED" = "$LATEST" ]; then
    log "Already on the latest version ($LATEST). Nothing to do."
    exit 0
fi

log ""
log "Models are stored separately (OLLAMA_MODELS / ~/.ollama) and are not touched by an"
log "upgrade. Generation may briefly stop while the service restarts."
confirm "Update Ollama ${INSTALLED:+from $INSTALLED }${LATEST:+to $LATEST}?" || { log "Cancelled."; exit 0; }

do_update || exit $?

sleep 3
NEW_DISK=$(installed_version)
NEW_RUN=$(running_version)
log "Now on disk    : ${NEW_DISK:-unknown}"
log "Now serving    : ${NEW_RUN:-not responding yet — check: systemctl status $(service_name 2>/dev/null)}"
if [ -n "$LATEST" ] && [ "$NEW_DISK" = "$LATEST" ]; then
    log "Updated successfully."
else
    log "Version is not the expected $LATEST — check the output above."
    log "Roll back with: $0 --rollback"
fi
