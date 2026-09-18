#!/usr/bin/env bash
# =============================================================================
# Recover an Ollama configuration that an upgrade overwrote
# =============================================================================
# The official installer rewrites /etc/systemd/system/ollama.service from its own
# template and keeps no backup, so every Environment= line the host had is gone. The
# usual casualties are OLLAMA_MODELS (every model appears to have vanished) and
# OLLAMA_HOST (the server silently goes back to 127.0.0.1 and the rest of the LAN
# gets connection refused).
#
# Guessing the old values is unnecessary. Ollama logs its whole resolved config on
# every start:
#
#   msg="server config" env="map[... OLLAMA_HOST:http://0.0.0.0:11434 \
#        OLLAMA_MODELS:/mnt/ocean2/Models ...]"
#
# so the journal still holds exactly what was in effect before the upgrade. This
# reads the last such line from before the upgrade, compares it with what is in
# effect now, and writes back only the settings that were actually lost.
#
# It writes a drop-in under ollama.service.d/ rather than editing the unit, because
# the installer overwrites the unit on every upgrade but never touches drop-ins.
#
# Usage:
#   ./ollama-recover-config.sh                 # show what was lost; changes nothing
#   ./ollama-recover-config.sh --apply         # write the drop-in and restart
#   ./ollama-recover-config.sh --before "2026-09-18 09:50"   # override the cutoff
#   ./ollama-recover-config.sh --history       # every distinct config the journal has
#
# Run it ON THE OLLAMA HOST, as root for --apply.
# =============================================================================
set -uo pipefail

BACKUP_DIR="/var/backups/ollama"
APPLY=0
MODE="report"
CUTOFF=""
RESTORE_ALL=0

while [ $# -gt 0 ]; do
    case "$1" in
        --apply)       APPLY=1 ;;
        --history)     MODE="history" ;;
        --restore-all) RESTORE_ALL=1 ;;
        --before)      CUTOFF="${2:-}"; shift ;;
        -h|--help) sed -n '2,31p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
    shift
done

log() { printf '[ollama-recover] %s\n' "$*"; }
die() { printf '[ollama-recover] ERROR: %s\n' "$*" >&2; exit 1; }

# The log records the RESOLVED config, which mixes deliberate settings with whatever
# the defaults were at the time. Nothing in the line says which is which, so the script
# must not pretend it can tell. Splitting them by consequence instead:
#
# CRITICAL - identity and placement. A changed value here breaks the host outright
# (models invisible, endpoint unreachable, wrong GPU), and no new default is ever a
# better answer than what the operator had. Restored automatically.
#
# These are all scalars that round-trip exactly: OLLAMA_HOST is logged as
# "http://0.0.0.0:11434" and Host() parses that same string back, OLLAMA_MODELS is a
# plain path, and the *_VISIBLE_DEVICES family are plain device lists.
CRITICAL="OLLAMA_MODELS OLLAMA_HOST CUDA_VISIBLE_DEVICES
          HIP_VISIBLE_DEVICES ROCR_VISIBLE_DEVICES HSA_OVERRIDE_GFX_VERSION
          GPU_DEVICE_ORDINAL"
#
# DERIVED - present in the log but NOT restorable, because what is logged is the
# resolved value rather than the input that produced it. Restoring these verbatim
# writes garbage:
#   OLLAMA_ORIGINS  is a Go []string rendered as "[a b c]" - space separated, brackets
#                   included - whereas the env var Ollama reads is COMMA separated. It is
#                   also the EFFECTIVE list, so it always contains the ~17 built-in
#                   defaults; pinning it freezes today's defaults forever and hides any
#                   the next version adds.
#   OLLAMA_REMOTES  same shape, same problem.
#   OLLAMA_DEBUG    logged as a level ("INFO"), set as a boolean/int.
# Reported so nothing is hidden, never written.
DERIVED="OLLAMA_ORIGINS OLLAMA_REMOTES"
#
# TUNING - performance knobs. Here an old value is usually an old DEFAULT, and pinning
# it back is actively harmful: restoring OLLAMA_FLASH_ATTENTION=false from a 0.18 log
# would disable something 0.34 turns on by default and that this host wants. These are
# written into the drop-in commented out, so the information survives and the decision
# stays with the operator. --restore-all activates them.
TUNING="OLLAMA_KEEP_ALIVE OLLAMA_CONTEXT_LENGTH OLLAMA_FLASH_ATTENTION
        OLLAMA_KV_CACHE_TYPE OLLAMA_NUM_PARALLEL OLLAMA_MAX_LOADED_MODELS
        OLLAMA_MAX_QUEUE OLLAMA_SCHED_SPREAD OLLAMA_GPU_OVERHEAD OLLAMA_LOAD_TIMEOUT
        OLLAMA_NOPRUNE OLLAMA_DEBUG OLLAMA_NEW_ENGINE OLLAMA_MULTIUSER_CACHE"

CARE="$CRITICAL $TUNING $DERIVED"

# systemd gives "%" a meaning of its own inside unit files (%h, %i, ...), so a literal
# percent - as in a proxy URL with percent-encoding - has to be doubled or the unit
# fails to parse or silently mangles the value.
esc_systemd() { printf '%s' "${1//%/%%}"; }

service_name() {
    local units
    units=$(systemctl list-unit-files --no-legend 2>/dev/null | grep -oE '^ollama[^ ]*\.service')
    printf '%s\n' "$units" | grep -qx 'ollama.service' && { echo "ollama.service"; return; }
    printf '%s\n' "$units" | head -1
}

# Each historical config as: <iso-timestamp><TAB><key:value key:value ...>
config_lines() {
    journalctl -u "$SVC" --no-pager -o short-iso 2>/dev/null |
        grep -F 'msg="server config"' |
        sed -E 's/^([^ ]+) .*env="map\[(.*)\]".*$/\1\t\2/'
}

# Pull one key out of a map string.
#
# Go renders the map as space-separated KEY:VALUE, which splits cleanly for every
# scalar. It does NOT split cleanly for a list: OLLAMA_ORIGINS comes out as
#     OLLAMA_ORIGINS:[http://localhost https://localhost http://127.0.0.1:*]
# so a naive space-split truncates it at the first element and would write a
# corrupt Environment= line back into the unit. Re-join tokens until the closing
# bracket when a value opens with one.
#
# Runs in a subshell with `set -f`. Splitting the map needs word splitting, which means
# leaving $map unquoted, which also invites pathname expansion - and the origins list
# genuinely contains both "[" and "*" (http://127.0.0.1:*). If a token ever matched a
# file in the working directory it would be silently replaced by that filename. Globbing
# off, word splitting on; the subshell keeps it from leaking into the rest of the script,
# which does rely on globbing to find the backup file.
map_get() ( set -f
    map="$1"; key="$2"; val=""; joining=0
    for tok in $map; do
        if [ "$joining" = 1 ]; then
            val+=" $tok"
            case "$tok" in *']') printf '%s' "$val"; exit 0 ;; esac
            continue
        fi
        case "$tok" in
            "$key":*)
                val="${tok#*:}"
                case "$val" in
                    '['*']') ;;                       # single-element list, already whole
                    '['*)    joining=1; continue ;;   # list continues into later tokens
                esac
                printf '%s' "$val"; exit 0
                ;;
        esac
    done
    [ "$joining" = 1 ] && printf '%s' "$val"   # unterminated list; return what we have
    exit 0
)

SVC=$(service_name)
[ -n "$SVC" ] || die "No ollama systemd unit found on this host."
command -v journalctl >/dev/null 2>&1 || die "journalctl is not available; cannot recover the old config."

ALL=$(config_lines)
[ -n "$ALL" ] || die "The journal has no Ollama \"server config\" lines. If it was vacuumed, the old
       settings are not recoverable from here -- set OLLAMA_MODELS and OLLAMA_HOST by hand."

if [ "$MODE" = "history" ]; then
    log "Every distinct config in the journal for $SVC (oldest first):"
    prev=""
    while IFS=$'\t' read -r ts map; do
        sig=""
        for k in $CARE; do v=$(map_get "$map" "$k"); [ -n "$v" ] && sig+="$k=$v "; done
        [ "$sig" = "$prev" ] && continue
        prev="$sig"
        printf '\n  %s\n' "$ts"
        for k in $CARE; do v=$(map_get "$map" "$k"); [ -n "$v" ] && printf '      %s=%s\n' "$k" "$v"; done
    done <<< "$ALL"
    exit 0
fi

# ── Which line counts as "before the upgrade"? ───────────────────────────────
# ollama-update.sh stamps its binary backup with the moment it ran, which is the
# most precise cutoff available. Otherwise fall back to the newest binary in the
# ollama install dir, and let --before override either.
if [ -z "$CUTOFF" ]; then
    newest=$(ls -1t "$BACKUP_DIR"/ollama-*-* 2>/dev/null | head -1)
    if [ -n "$newest" ]; then
        stamp=$(basename "$newest" | grep -oE '[0-9]{8}-[0-9]{6}$')
        if [ -n "$stamp" ]; then
            CUTOFF="${stamp:0:4}-${stamp:4:2}-${stamp:6:2} ${stamp:9:2}:${stamp:11:2}:${stamp:13:2}"
            log "Using the ollama-update.sh backup as the cutoff: $CUTOFF"
        fi
    fi
fi
[ -n "$CUTOFF" ] || die "Could not work out when the upgrade happened. Pass it:
       $0 --before \"2026-09-18 09:50\"     (see $0 --history)"

CUT_EPOCH=$(date -d "$CUTOFF" +%s 2>/dev/null) || die "Not a timestamp date(1) understands: $CUTOFF"

OLD=""; OLD_TS=""; NEW=""; NEW_TS=""
while IFS=$'\t' read -r ts map; do
    e=$(date -d "$ts" +%s 2>/dev/null) || continue
    if [ "$e" -lt "$CUT_EPOCH" ]; then OLD="$map"; OLD_TS="$ts"; fi
    NEW="$map"; NEW_TS="$ts"
done <<< "$ALL"

[ -n "$OLD" ] || die "No config recorded before $CUTOFF. Widen it with --before, or see --history."

log "Config before the upgrade : $OLD_TS"
log "Config in effect now      : $NEW_TS"
if [ "$OLD_TS" = "$NEW_TS" ]; then
    die "Those are the same journal entry -- the server has not started since the cutoff.
       Start it first, or pass an earlier --before."
fi

# ── What was lost? ───────────────────────────────────────────────────────────
LOST=""; TUNED=""
found=0

printf '\n'
log "CRITICAL settings that changed (restored automatically):"
for k in $CRITICAL; do
    o=$(map_get "$OLD" "$k"); n=$(map_get "$NEW" "$k")
    [ "$o" = "$n" ] && continue
    found=1
    printf '    %-26s was %-34s now %s\n' "$k" "${o:-<unset>}" "${n:-<unset>}"
    # Only restore something that actually had a value before. A setting that was
    # empty before and is set now came from the new default; leave it alone.
    [ -n "$o" ] && LOST+="Environment=\"$k=$(esc_systemd "$o")\""$'\n'
done
[ -n "$LOST" ] || printf '    (none)\n'

# Derived values: show the difference, refuse to write it, say what to do instead.
for k in $DERIVED; do
    o=$(map_get "$OLD" "$k"); n=$(map_get "$NEW" "$k")
    [ "$o" = "$n" ] && continue
    found=1
    printf '\n'
    log "$k changed, and is NOT restorable from this log:"
    log "    was: ${o:-<unset>}"
    log "    now: ${n:-<unset>}"
    log "  What the log shows is the RESOLVED list, rendered as a Go slice (space"
    log "  separated, in brackets). The environment variable takes a COMMA separated"
    log "  list, and setting the whole resolved list would also pin today's built-in"
    log "  defaults permanently. Set only the entries you added yourself, e.g.:"
    log "      Environment=\"$k=https://app.example.com,https://other.example.com\""
done

printf '\n'
log "TUNING settings that changed (NOT restored -- an old value here is usually just"
log "an old default, and 0.34 defaults are generally better):"
for k in $TUNING; do
    o=$(map_get "$OLD" "$k"); n=$(map_get "$NEW" "$k")
    [ "$o" = "$n" ] && continue
    found=1
    printf '    %-26s was %-34s now %s\n' "$k" "${o:-<unset>}" "${n:-<unset>}"
    [ -n "$o" ] && TUNED+="$k=$o"$'\n'
done
[ -n "$TUNED" ] || printf '    (none)\n'

[ "$found" = 1 ] || { printf '\n'; log "Nothing changed -- the upgrade preserved everything."; exit 0; }

# A recovered value can be faithfully restored and still be wrong, because it was
# already wrong before the upgrade. Call out the one that bites hardest.
ka=$(map_get "$OLD" OLLAMA_KEEP_ALIVE)
case "$ka" in
    ""|-*) ;;                                     # unset, or negative = never unload
    *[!0-9]*) ;;                                  # a duration like 30m0s; fine
    *)  printf '\n'
        log "WARNING: OLLAMA_KEEP_ALIVE was the bare number '$ka'. Ollama reads a bare"
        log "  integer as SECONDS, so that unloads the model ${ka}s after each request and"
        log "  every call then pays a full reload -- minutes, for a model this size."
        log "  If the intent was 'never unload', the value is -1. A duration also works:"
        log "  OLLAMA_KEEP_ALIVE=30m. Restoring '$ka' verbatim would preserve the bug."
        ;;
esac

if [ "$RESTORE_ALL" = "1" ] && [ -n "$TUNED" ]; then
    log "--restore-all: pinning the tuning values back too"
    while IFS= read -r kv; do [ -n "$kv" ] && LOST+="Environment=\"$(esc_systemd "$kv")\""$'\n'; done <<< "$TUNED"
    TUNED=""
fi

[ -n "$LOST" ] || [ -n "$TUNED" ] || { log "Nothing to restore."; exit 0; }

DIR="/etc/systemd/system/${SVC}.d"
FILE="$DIR/10-preserved-env.conf"

render() {
    echo "# Recovered by ollama-recover-config.sh from the pre-upgrade journal entry at"
    echo "# $OLD_TS. The official installer rewrites the main unit file on every upgrade"
    echo "# but never touches drop-ins, so keeping these here makes them survive."
    echo "[Service]"
    printf '%s' "$LOST"
    if [ -n "$TUNED" ]; then
        echo ""
        echo "# These also changed, but an old value here is usually an old default rather"
        echo "# than a deliberate choice, so they are left off. Uncomment any you actually"
        echo "# meant to set, then: systemctl daemon-reload && systemctl restart $SVC"
        while IFS= read -r kv; do
            [ -n "$kv" ] && echo "#Environment=\"$kv\""
        done <<< "$TUNED"
    fi
}

printf '\n'
log "Proposed $FILE:"
printf '\n'
render | sed 's/^/    /'
printf '\n'

if [ "$APPLY" != "1" ]; then
    log "Nothing written. Re-run with --apply to install it and restart $SVC."
    exit 0
fi

[ "$(id -u)" -eq 0 ] || die "--apply needs root."

if [ -f "$FILE" ]; then
    cp -a "$FILE" "$FILE.bak-$(date +%Y%m%d-%H%M%S)"
    log "Kept a copy of the existing drop-in alongside it"
fi
mkdir -p "$DIR"
render > "$FILE"
chmod 0644 "$FILE"
log "Wrote $FILE"

systemctl daemon-reload
log "Restarting $SVC"
timeout 120 systemctl restart "$SVC" ||
    die "Restart did not finish in 120s. Check: systemctl status $SVC"

sleep 3
HOST=$(map_get "$OLD" OLLAMA_HOST)
PROBE="${HOST:-http://127.0.0.1:11434}"
case "$PROBE" in http*) ;; *) PROBE="http://$PROBE" ;; esac
if curl -fsS -m 10 "$PROBE/api/version" >/dev/null 2>&1; then
    log "Serving on $PROBE: $(curl -fsS -m 10 "$PROBE/api/version")"
    log "Models visible: $(curl -fsS -m 15 "$PROBE/api/tags" | grep -o '"name"' | wc -l)"
else
    log "WARN: $PROBE is not answering yet. Check: journalctl -u $SVC -n 40 --no-pager"
fi
