#!/bin/sh
set -eu
: "${DATABASE_URL:?Set an isolated test database URL}"
: "${UPLOAD_ROOT:?Set an isolated upload directory}"
case "$DATABASE_URL" in
  *"/vtrace_business_test_"*) ;;
  *) echo 'Refusing a non-test database' >&2; exit 1 ;;
esac
export AI_BASE_URL='' AI_API_KEY='' AI_MODEL='' ASR_API_KEY=''
export ASR_BASE_URL="${VTRACE_TEST_ASR_BASE_URL:-}"
export LOCAL_ASR_ENABLED=false OCR_ENABLED=false AUTO_CREATE_SCHEMA=false
export APP_ENV=development ROOT_PATH='' EVIDENCE_RETRIEVER_PROFILE=lexical
# FastAPI rollback uses its existing local executor; Java submissions use the real Broker.
export ROCKETMQ_NAMESERVER=''
if [ -n "${VTRACE_API_PID_FILE:-}" ]; then echo "$$" > "$VTRACE_API_PID_FILE"; fi
exec python3 -m uvicorn seeit.main:app --host 0.0.0.0 --port "${VTRACE_PYTHON_PORT:-19090}"
