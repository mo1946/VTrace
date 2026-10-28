#!/bin/sh
# Test harness only. Runs the existing worker without patching its consumer or AI code.
set -eu
: "${DATABASE_URL:?Set an isolated test database URL}"
: "${ROCKETMQ_NAMESERVER:?Set a real NameServer address}"
case "$DATABASE_URL" in
  *"/vtrace_business_test_"*) ;;
  *) echo 'Refusing a database without the vtrace_business_test_ prefix' >&2; exit 1 ;;
esac
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
if [ -n "${VTRACE_LINUX_PACKAGES:-}" ]; then
  export PYTHONPATH="$VTRACE_LINUX_PACKAGES:$task_root/backend"
else
  export PYTHONPATH="$task_root/backend"
fi
export AI_BASE_URL='' AI_API_KEY='' AI_MODEL='' ASR_API_KEY=''
export ASR_BASE_URL="${VTRACE_TEST_ASR_BASE_URL:-}"
export LOCAL_ASR_ENABLED=false OCR_ENABLED=false AUTO_CREATE_SCHEMA=false
export EVIDENCE_RETRIEVER_PROFILE=lexical
# A completed smoke task must have been consumed from MQ, not picked up by the watchdog.
export QUEUED_TASK_FALLBACK_SECONDS=86400
if [ -n "${VTRACE_WORKER_PID_FILE:-}" ]; then echo "$$" > "$VTRACE_WORKER_PID_FILE"; fi
cd "$task_root/backend"
exec python3 -m seeit.worker
