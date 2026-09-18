#!/usr/bin/env bash
# =============================================================================
# Ollama: find models the engine cannot drive, and blobs nothing references
# =============================================================================
# Two different kinds of waste accumulate in an Ollama model store, and only one
# of them is visible from `ollama list`:
#
#   1. MODELS THE ENGINE CANNOT USE. A model is only usable through /api/chat if
#      something can render the message list into a prompt: a Go template that
#      walks .Messages/.System, a Jinja chat template out of the GGUF metadata
#      (Ollama 0.34+ reads these), or a built-in renderer for the architecture.
#      With none of those, Ollama falls back to the bare "{{ .Prompt }}" and the
#      model silently receives one token of the prompt and answers nonsense. It
#      still appears in `ollama list`, still takes tens of gigabytes.
#
#      Worth knowing before deleting anything: this is often the SERVER's fault,
#      not the model's. On this deployment a 21 GB model looked unusable under
#      0.18.3 and became fully usable under 0.34.2 with its weights untouched,
#      because 0.18.3 could not parse the Jinja template the GGUF already had.
#      So this script REPORTS unusable models and needs --apply to remove them,
#      and it tells you the server version so you can rule that out first.
#
#   2. ORPHANED BLOBS. The blob store is content-addressed and shared between
#      models. Interrupted pulls, replaced tags and manual manifest edits leave
#      blobs that no manifest references any more. Nothing lists them and they
#      are frequently the larger win -- a single orphaned weights blob is
#      multiple gigabytes.
#
# Usage:
#   ./ollama-cleanup.sh                 # report only; changes nothing
#   ./ollama-cleanup.sh --apply         # delete what was reported
#   ./ollama-cleanup.sh --orphans-only  # ignore models, just reclaim blobs
#   ./ollama-cleanup.sh --models-only   # ignore blobs, just unusable models
#   ./ollama-cleanup.sh --keep NAME     # never touch this model (repeatable)
#
# Run it ON THE OLLAMA HOST. --apply needs write access to the model store.
# =============================================================================
set -uo pipefail

API="${OLLAMA_HOST:-http://127.0.0.1:11434}"
case "$API" in http*) ;; *) API="http://$API" ;; esac

APPLY=0
DO_MODELS=1
DO_ORPHANS=1
KEEP=()

while [ $# -gt 0 ]; do
    case "$1" in
        --apply)        APPLY=1 ;;
        --orphans-only) DO_MODELS=0 ;;
        --models-only)  DO_ORPHANS=0 ;;
        --keep)         KEEP+=("${2:-}"); shift ;;
        -h|--help)      sed -n '2,38p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
    shift
done

log()  { printf '[ollama-cleanup] %s\n' "$*"; }
die()  { printf '[ollama-cleanup] ERROR: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

human() {  # bytes -> human readable, without requiring numfmt
    awk -v b="${1:-0}" 'BEGIN{
        split("B KB MB GB TB",u," "); i=1
        while (b>=1024 && i<5) { b/=1024; i++ }
        printf (i==1 ? "%d %s" : "%.1f %s"), b, u[i]
    }'
}

have curl || die "curl is required"

# ── Where is the model store? ────────────────────────────────────────────────
# The unit's Environment= is authoritative; fall back to the documented defaults.
find_store() {
    local v
    v=$(systemctl show -p Environment ollama.service 2>/dev/null | tr ' ' '\n' |
        sed -n 's/^OLLAMA_MODELS=//p' | tail -1)
    [ -n "$v" ] && { echo "$v"; return; }
    [ -n "${OLLAMA_MODELS:-}" ] && { echo "$OLLAMA_MODELS"; return; }
    for d in /usr/share/ollama/.ollama/models "$HOME/.ollama/models" /var/lib/ollama/.ollama/models; do
        [ -d "$d" ] && { echo "$d"; return; }
    done
}

STORE=$(find_store)
[ -n "$STORE" ] && [ -d "$STORE" ] || die "Cannot find the model store. Set OLLAMA_MODELS and re-run.
       Tried the ollama.service unit, \$OLLAMA_MODELS and the usual defaults."
[ -d "$STORE/blobs" ] || die "$STORE has no blobs/ directory -- is that really the model store?"

VERSION=$(curl -fsS -m 8 "$API/api/version" 2>/dev/null |
          grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)

log "Model store : $STORE"
log "Server      : ${VERSION:-not responding on $API}"
[ ${#KEEP[@]} -gt 0 ] && log "Never touch : ${KEEP[*]}"

STORE_BYTES=$(du -sb "$STORE" 2>/dev/null | cut -f1)
log "Store size  : $(human "${STORE_BYTES:-0}")"

RECLAIM=0

# ── 1. Models the engine cannot drive ────────────────────────────────────────
UNUSABLE=()
if [ "$DO_MODELS" = 1 ]; then
    printf '\n'
    if [ -z "$VERSION" ]; then
        log "Skipping the model check: the server is not answering, and usability can only be"
        log "determined from /api/show. Start Ollama and re-run, or use --orphans-only."
    elif ! have python3; then
        log "Skipping the model check: python3 is needed to read /api/show. Use --orphans-only."
    else
        log "Checking which models can actually be driven through /api/chat ..."
        while IFS=$'\t' read -r name usable why size; do
            [ -n "$name" ] || continue
            skip=0
            for k in ${KEEP[@]+"${KEEP[@]}"}; do [ "$k" = "$name" ] && skip=1; done
            if [ "$usable" = "yes" ]; then
                printf '    OK       %-56s %s\n' "$name" "$why"
            elif [ "$skip" = 1 ]; then
                printf '    KEEP     %-56s %s (unusable, but --keep)\n' "$name" "$why"
            else
                printf '    UNUSABLE %-56s %s  [%s]\n' "$name" "$why" "$(human "$size")"
                UNUSABLE+=("$name")
                RECLAIM=$((RECLAIM + size))
            fi
        done < <(
            curl -fsS -m 20 "$API/api/tags" 2>/dev/null |
            python3 -c '
import json, sys, urllib.request

def show(name):
    req = urllib.request.Request("'"$API"'/api/show",
        data=json.dumps({"model": name}).encode(),
        headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=60))

try:
    models = json.load(sys.stdin).get("models", [])
except Exception:
    sys.exit(0)

for m in models:
    name, size = m["name"], m.get("size", 0)
    try:
        d = show(name)
    except Exception as e:
        print(f"{name}\tunknown\tcould not inspect ({str(e)[:30]})\t{size}")
        continue
    t = (d.get("template") or "").strip()
    caps = d.get("capabilities") or []
    # Same rule as the application: Go template, Jinja chat template, or a
    # capability that is unreachable without structured messages.
    if ".Messages" in t or ".System" in t:
        print(f"{name}\tyes\tGo template renders messages\t{size}")
    elif "{%" in t and ("messages" in t or "im_start" in t or "add_generation_prompt" in t):
        print(f"{name}\tyes\tJinja chat template ({len(t)} chars)\t{size}")
    elif "tools" in caps or "thinking" in caps:
        print(f"{name}\tyes\tbuilt-in renderer (capabilities={caps})\t{size}")
    else:
        print(f"{name}\tno\tno renderer: template is {len(t)} chars, capabilities={caps}\t{size}")
' 2>/dev/null
        )
    fi
fi

# ── 2. Blobs nothing references ──────────────────────────────────────────────
ORPHANS=()
ORPHAN_BYTES=0
if [ "$DO_ORPHANS" = 1 ]; then
    printf '\n'
    log "Looking for blobs no manifest references ..."
    REFS=$(mktemp); trap 'rm -f "$REFS"' EXIT
    # Manifests are JSON; every blob reference is a sha256:<64 hex> digest, and
    # grepping for the digest shape is robust without needing a JSON parser.
    if [ -d "$STORE/manifests" ]; then
        grep -rhoE 'sha256:[0-9a-f]{64}' "$STORE/manifests" 2>/dev/null |
            sed 's/:/-/' | sort -u > "$REFS"
    else
        : > "$REFS"
        log "  WARNING: $STORE/manifests does not exist. Treating every blob as orphaned would"
        log "  be catastrophic, so nothing will be removed. Check the store path."
        DO_ORPHANS=0
    fi
fi

if [ "$DO_ORPHANS" = 1 ]; then
    refcount=$(wc -l < "$REFS")
    log "  $refcount distinct blobs are referenced by manifests"
    if [ "$refcount" -eq 0 ]; then
        log "  Refusing to continue: no manifest references any blob, which almost certainly"
        log "  means the store layout is not what this script expects."
        DO_ORPHANS=0
    fi
fi

if [ "$DO_ORPHANS" = 1 ]; then
    while IFS= read -r blob; do
        base=$(basename "$blob")
        grep -qxF "$base" "$REFS" && continue
        sz=$(stat -c '%s' "$blob" 2>/dev/null || echo 0)
        ORPHANS+=("$blob")
        ORPHAN_BYTES=$((ORPHAN_BYTES + sz))
        printf '    ORPHAN   %-56s %s\n' "$base" "$(human "$sz")"
    done < <(find "$STORE/blobs" -type f -name 'sha256-*' 2>/dev/null | sort)
    if [ ${#ORPHANS[@]} -eq 0 ]; then
        log "  No orphaned blobs -- the store is tidy."
    else
        log "  ${#ORPHANS[@]} orphaned blob(s), $(human "$ORPHAN_BYTES")"
        RECLAIM=$((RECLAIM + ORPHAN_BYTES))
    fi
fi

# ── Summary and action ───────────────────────────────────────────────────────
printf '\n'
if [ ${#UNUSABLE[@]} -eq 0 ] && [ ${#ORPHANS[@]} -eq 0 ]; then
    log "Nothing to clean up."
    exit 0
fi

log "Would reclaim $(human "$RECLAIM")"
[ ${#UNUSABLE[@]} -gt 0 ] && log "  models : ${UNUSABLE[*]}"
[ ${#ORPHANS[@]} -gt 0 ] && log "  blobs  : ${#ORPHANS[@]} file(s)"

if [ ${#UNUSABLE[@]} -gt 0 ]; then
    printf '\n'
    log "Before deleting an unusable model, rule out the server: a model with no renderer on"
    log "an old Ollama can become fully usable on a new one with the same weights. This host"
    log "runs ${VERSION:-unknown}; the current release is what ollama-update.sh --check reports."
fi

if [ "$APPLY" != "1" ]; then
    printf '\n'
    log "Nothing was deleted. Re-run with --apply to remove the above."
    exit 0
fi

printf '\n'
for m in ${UNUSABLE[@]+"${UNUSABLE[@]}"}; do
    log "ollama rm $m"
    if have ollama; then
        ollama rm "$m" || log "  WARN: could not remove $m"
    else
        curl -fsS -m 60 -X DELETE "$API/api/delete" \
             -H 'Content-Type: application/json' \
             -d "{\"model\":\"$m\"}" >/dev/null || log "  WARN: could not remove $m"
    fi
done

for b in ${ORPHANS[@]+"${ORPHANS[@]}"}; do
    rm -f "$b" || log "  WARN: could not remove $b"
done
[ ${#ORPHANS[@]} -gt 0 ] && log "Removed ${#ORPHANS[@]} orphaned blob(s)"

NOW=$(du -sb "$STORE" 2>/dev/null | cut -f1)
log "Store size: $(human "${STORE_BYTES:-0}") -> $(human "${NOW:-0}")"
log "Done. Verify with: ollama list"
