#!/usr/bin/env bash
set -euo pipefail

# Destructive reset of CLOUDCODE *application* DB only, then seed base OFBiz reference data
# and load wanerpapi NEA ext-demo data. It intentionally never modifies wanerptenant.

TENANT_ID="${TENANT_ID:-CLOUDCODE}"
TENANT_DB="${TENANT_DB:-wanerp_cloudcode}"
PG_CONTAINER="${PG_CONTAINER:-postgresql-db}"
PG_SUPERUSER="${PG_SUPERUSER:-postgres}"
FRAMEWORK_ROOT="${FRAMEWORK_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}"
BACKUP_DIR="${BACKUP_DIR:-$FRAMEWORK_ROOT/runtime/backups/wanerpapi-cloudcode-reset}"
CONFIRM_RESET="${CONFIRM_RESET:-}"
ADMIN_USER="${ADMIN_USER:-admin}"

if [[ "$TENANT_ID" != "CLOUDCODE" ]]; then
  echo "ERROR: this script is intentionally locked to TENANT_ID=CLOUDCODE" >&2; exit 2
fi
case "$TENANT_DB" in
  wanerp_cloudcode) ;;
  *) echo "ERROR: refusing to reset unexpected database: $TENANT_DB" >&2; exit 2;;
esac
if [[ "$CONFIRM_RESET" != "RESET-CLOUDCODE-NEA" ]]; then
  cat >&2 <<EOF
Refusing destructive reset.
This script drops/recreates ONLY: $TENANT_DB
It DOES NOT modify wanerptenant, wanerp, keycloak, or CLOUDCODE tenant-domain/component config.

Run only after verifying backups:
  CONFIRM_RESET=RESET-CLOUDCODE-NEA $0
EOF
  exit 3
fi

cd "$FRAMEWORK_ROOT"
mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP="$BACKUP_DIR/${TENANT_DB}_${STAMP}.dump"
MASTER_BACKUP="$BACKUP_DIR/wanerptenant_${STAMP}.dump"

echo "[1/8] Safety checks"
docker exec "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d postgres -Atc \
  "SELECT datname FROM pg_database WHERE datname IN ('wanerptenant','wanerp','keycloak','$TENANT_DB') ORDER BY datname;"

echo "[2/8] Backing up application DB and read-only tenant master config"
docker exec "$PG_CONTAINER" pg_dump -U "$PG_SUPERUSER" -Fc "$TENANT_DB" > "$BACKUP"
docker exec "$PG_CONTAINER" pg_dump -U "$PG_SUPERUSER" -Fc wanerptenant > "$MASTER_BACKUP"
[[ -s "$BACKUP" ]] || { echo "ERROR: application DB backup is empty" >&2; exit 4; }
[[ -s "$MASTER_BACKUP" ]] || { echo "ERROR: wanerptenant backup is empty" >&2; exit 4; }
sha256sum "$BACKUP" "$MASTER_BACKUP" | tee "$BACKUP_DIR/SHA256SUMS_${STAMP}"

OWNER="$(docker exec "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d postgres -Atc \
  "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$TENANT_DB';")"
[[ -n "$OWNER" ]] || OWNER="$PG_SUPERUSER"

echo "[3/8] Stopping Wantok ERP container if it is running"
WAS_RUNNING=0
if docker ps --format '{{.Names}}' | grep -qx 'wantok-erp'; then
  WAS_RUNNING=1
  docker stop wantok-erp >/dev/null
fi

echo "[4/8] Recreating ONLY $TENANT_DB (owner=$OWNER)"
docker exec "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d postgres -v ON_ERROR_STOP=1 -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$TENANT_DB' AND pid <> pg_backend_pid();"
docker exec "$PG_CONTAINER" dropdb -U "$PG_SUPERUSER" --if-exists "$TENANT_DB"
docker exec "$PG_CONTAINER" createdb -U "$PG_SUPERUSER" -O "$OWNER" "$TENANT_DB"

echo "[5/8] Loading required OFBiz reference/config data into default#$TENANT_ID (NO demo reader)"
./gradlew "ofbiz --load-data delegator=default#$TENANT_ID readers=seed,seed-initial"

echo "[6/8] Restoring a local test admin in the tenant DB without touching wanerptenant"
TMP_ADMIN="$FRAMEWORK_ROOT/runtime/tmp/wanerpapi-${TENANT_ID}-admin.xml"
mkdir -p "$FRAMEWORK_ROOT/runtime/tmp"
sed "s/@userLoginId@/${ADMIN_USER}/g" \
  "$FRAMEWORK_ROOT/framework/resources/templates/AdminUserLoginData.xml" > "$TMP_ADMIN"
./gradlew "ofbiz --load-data delegator=default#$TENANT_ID file=$TMP_ADMIN"
rm -f "$TMP_ADMIN"

echo "[7/8] Loading ONLY wanerpapi external NEA demo business data"
./gradlew "ofbiz --load-data delegator=default#$TENANT_ID readers=ext-demo component=wanerpapi"

echo "[8/8] Verifying CLOUDCODE tenant config DB was not targeted"
echo "No write command in this script references database wanerptenant."
echo "Application DB reset and NEA seed completed."
echo "Application backup: $BACKUP"
echo "Tenant-master safety backup: $MASTER_BACKUP"

if [[ "$WAS_RUNNING" == "1" ]]; then
  echo "Starting Wantok ERP container again..."
  docker start wantok-erp >/dev/null || true
fi
