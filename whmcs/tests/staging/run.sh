#!/usr/bin/env bash
# Reproducible end-to-end staging run for the TermiNow Store<->WHMCS->Firebase integration.
#
# WHAT THIS IS — and IS NOT:
#   * MOCKED-WHMCS: there is NO WHMCS install. The real catalog_lib/entitlement_lib code runs, but the
#     WHMCS runtime seam (localAPI/GetClientsProducts) is fed synthetic fixtures, and hooks.php is not
#     fired by a real admin action. This does NOT prove the live WHMCS runtime.
#   * EMULATED-FIREBASE: the REAL writer (firebase/functions/src/entitlement.js) runs over the local
#     Firestore EMULATOR via the Admin SDK. This is NOT the deployed Cloud Function or a real project.
#   * The Store (terminowai) is optional (set STORE_DIR) and runs locally in sandbox/dev mode.
# Treat green here as "the code paths and contracts agree", NOT as proof of live integration.
#
# Prerequisites: Node >= 22.13 (node on PATH), php with pdo_sqlite+curl, Java 17+, and the firebase CLI
#   resolvable (firebase/functions/node_modules/.bin/firebase or `firebase` on PATH). PR #12's
#   firebase/functions/src/entitlement.js must be present (same repo once #12 is merged).
# Usage:  ./run.sh            # core path + lifecycle battery, gate OFF then ON, with assertions
#         STORE_DIR=/path/to/terminowai ./run.sh    # also verify catalog -> Store import
set -u
HARNESS="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HARNESS/../../.." && pwd)"
FUNCTIONS_DIR="${TERMINOW_FUNCTIONS_DIR:-$REPO_ROOT/firebase/functions}"
WORK="${STAGING_WORK:-$HARNESS/.work}"
EMU_PORT="${EMU_PORT:-8085}"; WRITER_PORT="${WRITER_PORT:-9321}"; FEED_PORT="${FEED_PORT:-9310}"
PROJECT="${GCLOUD_PROJECT:-demo-terminow}"
FAILS=0; PIDS=()
say(){ printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok(){ printf '  ok   %s\n' "$*"; }
bad(){ printf '  FAIL %s\n' "$*"; FAILS=$((FAILS+1)); }
assert(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (want [$3] got [$2])"; fi; }
cleanup(){ for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null; done; pkill -f "cloud-firestore-emulator" 2>/dev/null; pkill -f "catalog-feed.php" 2>/dev/null; }
trap cleanup EXIT

command -v node >/dev/null || { echo "node (>=22.13) required"; exit 2; }
[ -f "$FUNCTIONS_DIR/src/entitlement.js" ] || { echo "missing $FUNCTIONS_DIR/src/entitlement.js (needs PR #12); set TERMINOW_FUNCTIONS_DIR"; exit 2; }
FIREBASE_BIN="$FUNCTIONS_DIR/node_modules/.bin/firebase"; [ -x "$FIREBASE_BIN" ] || FIREBASE_BIN="firebase"
FIREBASE_PROJECT_DIR="$(dirname "$FUNCTIONS_DIR")"   # holds firebase.json (defines the firestore port)
[ -f "$FIREBASE_PROJECT_DIR/firebase.json" ] || { echo "missing $FIREBASE_PROJECT_DIR/firebase.json"; exit 2; }

rm -rf "$WORK"; mkdir -p "$WORK/data" "$WORK/run" "$WORK/logs"
# Ephemeral staging secrets — generated per run, 0600, never printed or committed.
( umask 077; openssl rand -hex 20 | tr -d '\n' > "$WORK/run/ent_secret"; openssl rand -hex 20 | tr -d '\n' > "$WORK/run/catalog_secret"; )

export PATH NODE_PATH="$FUNCTIONS_DIR/node_modules"
export FIRESTORE_EMULATOR_HOST="127.0.0.1:$EMU_PORT" GCLOUD_PROJECT="$PROJECT"
export CATALOG_DB="$WORK/data/whmcs.sqlite" STAGING_DATA="$WORK/data"
export ENTITLEMENT_URL="http://127.0.0.1:$WRITER_PORT/" ENTITLEMENT_SECRET_FILE="$WORK/run/ent_secret"
export CATALOG_SECRET_FILE="$WORK/run/catalog_secret"
FB(){ node "$HARNESS/fb.js" "$@"; }
OB(){ php "$HARNESS/outbox.php" "$@"; }
wait_http(){ for _ in $(seq 1 60); do curl -s -o /dev/null "$1" 2>/dev/null && return 0; sleep 0.5; done; return 1; }
featf(){ FB read "$1" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const d=JSON.parse(s||"null")||{};const w=(d.sources||{}).whmcs||{};const f=(w.features||{})["terminow-premium"]||{};const g=(d.sources||{}).google_play;process.stdout.write([f.active?1:0,f.state||"-",w.premium?1:0,d.premium?1:0,g?(g.premium?1:0):"-"].join("|"))})'; }

say "start Firestore emulator (EMULATED-FIREBASE)"
( cd "$FIREBASE_PROJECT_DIR" && exec nice -n 19 "$FIREBASE_BIN" emulators:start --only firestore --project "$PROJECT" ) > "$WORK/logs/emulator.log" 2>&1 &
EMU_PID=$!; PIDS+=($EMU_PID)
# The emulator is a cold JVM and can take well over a minute to bind in a constrained container.
for _ in $(seq 1 240); do
  curl -s -o /dev/null "http://127.0.0.1:$EMU_PORT/" 2>/dev/null && break
  grep -q "All emulators ready" "$WORK/logs/emulator.log" 2>/dev/null && break
  kill -0 $EMU_PID 2>/dev/null || { echo "emulator process exited; see $WORK/logs/emulator.log"; exit 2; }
  sleep 1
done
curl -s -o /dev/null "http://127.0.0.1:$EMU_PORT/" 2>/dev/null || { echo "emulator did not open :$EMU_PORT in time; see $WORK/logs/emulator.log"; exit 2; }
ok "emulator on :$EMU_PORT"

say "start REAL writer over the emulator, gate OFF"
WHMCS_ENTITLEMENT_SECRET="$(cat "$WORK/run/ent_secret")" WRITER_PORT="$WRITER_PORT" node "$HARNESS/writer-staging.js" > "$WORK/logs/writer.log" 2>&1 &
WRITER_PID=$!; PIDS+=($WRITER_PID)
grep -q . "$WORK/logs/writer.log" 2>/dev/null; for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$WRITER_PORT/" && break; sleep 0.3; done
assert "writer GET -> 405" "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$WRITER_PORT/)" "405"

say "start MOCKED-WHMCS catalog feed + map catalog"
nice -n 19 php -S "127.0.0.1:$FEED_PORT" "$HARNESS/catalog-feed.php" > "$WORK/logs/feed.log" 2>&1 &
PIDS+=($!)
wait_http "http://127.0.0.1:$FEED_PORT/terminow/catalog.php"
OB map >/dev/null && ok "catalog mapped"
# Signed feed fetch
TS=$(date +%s); BODY='{"locale":null}'; SIG=$(printf '%s' "$TS.$BODY" | openssl dgst -sha256 -hmac "$(cat "$WORK/run/catalog_secret")" | awk '{print $2}')
ITEMS=$(curl -s -X POST "http://127.0.0.1:$FEED_PORT/terminow/catalog.php" -H "X-TermiNow-Timestamp: $TS" -H "X-TermiNow-Signature: $SIG" -d "$BODY" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);process.stdout.write(String(j.items.length))})')
assert "signed feed returns 3 items" "$ITEMS" "3"
assert "feed rejects a bad signature" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$FEED_PORT/terminow/catalog.php" -H "X-TermiNow-Timestamp: $TS" -H 'X-TermiNow-Signature: bad' -d "$BODY")" "401"

say "seed links + a pre-existing Google Play grant (preservation probe)"
FB reset >/dev/null; FB seed-link alice 42 >/dev/null; FB seed-link dave 46 >/dev/null
FB seed-source alice google_play '{"premium":true,"tier":"premium","checked":1,"orderId":"GPA.TEST"}' >/dev/null
ok "seeded"

say "lifecycle (gate OFF): activation / renewal / suspend / expire / cancel"
cp "$HARNESS/fixtures/services_active.json"    "$WORK/data/services_42.json"; OB sync 42 alice >/dev/null; assert "activation records whmcs, does NOT grant, preserves GP" "$(featf alice)" "1|Active|0|1|1"
cp "$HARNESS/fixtures/services_active.json"    "$WORK/data/services_42.json"; R=$(OB sync 42 alice); assert "re-sync identical is idempotent (enqueued=0)" "$(echo "$R" | grep -o 'enqueued=[0-9]*')" "enqueued=0"
cp "$HARNESS/fixtures/services_suspended.json" "$WORK/data/services_42.json"; OB sync 42 alice >/dev/null; assert "suspension -> inactive/Suspended, GP preserved" "$(featf alice)" "0|Suspended|0|1|1"
cp "$HARNESS/fixtures/services_expired.json"   "$WORK/data/services_42.json"; OB sync 42 alice >/dev/null; assert "Active-past-due -> Expired/inactive" "$(featf alice)" "0|Expired|0|1|1"
cp "$HARNESS/fixtures/services_cancelled.json" "$WORK/data/services_42.json"; OB sync 42 alice >/dev/null; assert "cancellation -> Cancelled/inactive" "$(featf alice)" "0|Cancelled|0|1|1"

say "unlinked uid -> held (409), nothing written; then retry after linking"
cp "$HARNESS/fixtures/services_active.json" "$WORK/data/services_99.json"
R=$(OB sync 99 mallory); assert "unlinked held" "$(echo "$R" | grep -o 'held":[0-9]*' | head -1)" 'held":1'
assert "writer wrote nothing for unlinked uid" "$(FB read mallory)" "null"
FB seed-link mallory 99 >/dev/null
HELD_ID=$(OB events | awk '/uid=mallory/ && /held/ {gsub("#","",$1);print $1; exit}')
OB retry "$HELD_ID" >/dev/null; assert "retry after link delivers" "$(featf mallory | cut -d'|' -f1)" "1"

say "reconcile: drift preview (no write) then apply"
cp "$HARNESS/fixtures/services_active.json" "$WORK/data/services_42.json"   # reactivated, event 'missed'
R=$(OB reconcile 42 alice preview); assert "preview reports drift, writes nothing" "$(featf alice | cut -d'|' -f2)" "Cancelled"
OB reconcile 42 alice apply >/dev/null; assert "apply corrects to Active" "$(featf alice | cut -d'|' -f2)" "Active"

say "writer auth edges"
assert "bad signature -> 401" "$(TS=$(date +%s); curl -s -o /dev/null -w '%{http_code}' -X POST "$ENTITLEMENT_URL" -H "X-TermiNow-Timestamp: $TS" -H 'X-TermiNow-Signature: bad' -d '{}')" "401"

say "GATE ON (isolated): restart writer with WHMCS_GRANTS_PREMIUM=1"
kill "$WRITER_PID" 2>/dev/null; sleep 1
WHMCS_ENTITLEMENT_SECRET="$(cat "$WORK/run/ent_secret")" WRITER_PORT="$WRITER_PORT" WHMCS_GRANTS_PREMIUM=1 node "$HARNESS/writer-staging.js" > "$WORK/logs/writer_on.log" 2>&1 &
PIDS+=($!); for _ in $(seq 1 40); do curl -s -o /dev/null "$ENTITLEMENT_URL" && break; sleep 0.3; done
cp "$HARNESS/fixtures/services_active.json" "$WORK/data/services_46.json"; OB sync 46 dave >/dev/null
assert "gate ON: sole WHMCS source grants Premium" "$(featf dave)" "1|Active|1|1|-"
cp "$HARNESS/fixtures/services_suspended.json" "$WORK/data/services_46.json"; OB sync 46 dave >/dev/null
assert "gate ON: suspend drops Premium (sole source)" "$(featf dave)" "0|Suspended|0|0|-"
cp "$HARNESS/fixtures/services_renew.json" "$WORK/data/services_42.json"; OB sync 42 alice >/dev/null
assert "gate ON: WHMCS grants while GP also present" "$(featf alice)" "1|Active|1|1|1"
cp "$HARNESS/fixtures/services_cancelled.json" "$WORK/data/services_42.json"; OB sync 42 alice >/dev/null
assert "gate ON: WHMCS withdrawn, GP preserved keeps Premium" "$(featf alice)" "0|Cancelled|0|1|1"

if [ -n "${STORE_DIR:-}" ]; then
  say "OPTIONAL: Store imports the catalog feed (catalog -> Store)"
  ( cd "$STORE_DIR" && PORT=8719 AIVORA_DB="$WORK/data/store.sqlite" WHMCS_CATALOG_URL="http://127.0.0.1:$FEED_PORT/terminow/catalog.php" \
      WHMCS_CATALOG_SECRET="$(cat "$WORK/run/catalog_secret")" WHMCS_CATALOG_TTL_MS=60000 GOOGLE_SIGNIN=0 node src/server.mjs > "$WORK/logs/store.log" 2>&1 & )
  wait_http "http://127.0.0.1:8719/api/catalog"
  IMP=$(grep -o 'whmcs_catalog_import[^}]*' "$WORK/logs/store.log" | tail -1)
  echo "  store import: $IMP"
  echo "$IMP" | grep -q '"imported":2' && ok "Store imported 2 items" || bad "Store import != 2 (see $WORK/logs/store.log)"
  pkill -f "terminowai.*server.mjs" 2>/dev/null; pkill -f "$WORK/data/store.sqlite" 2>/dev/null
fi

say "RESULT"
if [ "$FAILS" -eq 0 ]; then echo "  all staging checks passed (MOCKED-WHMCS + EMULATED-FIREBASE — not live integration)"; else echo "  $FAILS check(s) failed"; fi
exit $([ "$FAILS" -eq 0 ] && echo 0 || echo 1)
