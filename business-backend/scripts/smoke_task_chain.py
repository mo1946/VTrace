"""Assert real Java HTTP -> MQ -> unmodified Python Worker -> MySQL completion.

Requires a separate Worker process started with run_worker_smoke.sh and an isolated DB.
Never calls process_analysis itself. A broker acknowledgment alone is not a passing test.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import time
from urllib.parse import urlencode
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url


def main() -> None:
    url = os.environ["VTRACE_TEST_PYTHON_DB_URL"]
    if not (make_url(url).database or "").startswith("vtrace_business_test_"):
        raise SystemExit("Refusing a non-test database")
    root = Path(__file__).resolve().parents[1]
    fixture = json.loads(Path(os.environ.get("VTRACE_TEST_FIXTURE_FILE", root / "target/python-fixture.json")).read_text(encoding="utf-8"))
    worker_log = Path(os.environ["VTRACE_WORKER_LOG"])
    base = os.environ.get("VTRACE_TEST_JAVA_URL", "http://127.0.0.1:18080")
    goal = "总结视频 " + str(uuid4())
    request = Request(base + "/analysis/ai?" + urlencode({"id": fixture["mediaId"], "goal": goal}),
                      headers={"Authorization": "Bearer " + fixture["token"]}, method="POST", data=b"")
    with urlopen(request, timeout=15) as response:
        submission = json.load(response)
        if response.status != 202:
            raise AssertionError(f"Task not accepted: {submission}")
    task_id = submission["taskId"]
    engine = create_engine(url)
    try:
        if os.environ.get("VTRACE_EXPECT_MQ_FAILURE") == "1":
            assert submission["dispatchStatus"] == "PENDING", submission
            with engine.connect() as connection:
                pending = connection.execute(text(
                    "SELECT state, active_key FROM analysis_tasks WHERE id=:id"), {"id": task_id}).mappings().one()
            assert pending["state"] == "QUEUED" and pending["active_key"]
            try:
                urlopen(request, timeout=15)
                raise AssertionError("Duplicate should return 409")
            except HTTPError as error:
                assert error.code == 409
                assert json.load(error)["taskId"] == task_id
            result = {"taskId": task_id, "dispatchStatus": "PENDING", "state": pending["state"],
                      "activeKeyRetained": True, "duplicateStatus": 409}
            (root / "target/mq-failure-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
            print(json.dumps(result))
            return
        assert submission.get("dispatchStatus") == "SENT", f"Not acknowledged by real MQ: {submission}"
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            with engine.connect() as connection:
                row = connection.execute(text(
                    "SELECT state, result, trace_json, error, attempt_count, active_key "
                    "FROM analysis_tasks WHERE id=:id"), {"id": task_id}).mappings().one()
            if row["state"] == "FAILED":
                raise AssertionError(f"Worker failed: {row['error']}")
            if row["state"] == "COMPLETED":
                break
            time.sleep(1)
        else:
            raise AssertionError(f"Worker completion timeout: task={task_id}, state={row['state']}")
        log = worker_log.read_text(encoding="utf-8", errors="replace")
        assert f"task_message_received type=analysis task_id={task_id}" in log, "No actual MQ consumer receipt"
        assert not any("queued_task_fallback" in line and task_id in line for line in log.splitlines()), \
            "Watchdog is not an MQ chain test"
        assert row["result"], "No report persisted"
        assert row["active_key"] is None, "Worker did not release DB active key"
        trace = json.loads(row["trace_json"])
        assert trace["provider"] == "MockProvider", "Smoke test must not spend real model credits"
        status_request = Request(base + "/analysis/task-status?" + urlencode({"taskId": task_id}),
                                 headers={"Authorization": "Bearer " + fixture["token"]})
        with urlopen(status_request, timeout=10) as response:
            assert json.load(response)["state"] == "COMPLETED"
        result = {"taskId": task_id, "dispatchStatus": "SENT", "workerMqReceipt": True,
                  "state": row["state"], "attemptCount": row["attempt_count"],
                  "provider": trace["provider"], "reportChars": len(row["result"]),
                  "watchdogUsed": False}
        if os.environ.get("VTRACE_TEST_REDIS_URL"):
            import redis
            with engine.connect() as connection:
                lock_key = connection.execute(text(
                    "SELECT CONCAT('analysis:active:', media_id, ':', LEFT(goal_hash, 24)) "
                    "FROM analysis_tasks WHERE id=:id"), {"id": task_id}).scalar_one()
            with redis.Redis.from_url(os.environ["VTRACE_TEST_REDIS_URL"]) as cache:
                assert cache.get(lock_key) is None, "Worker did not release the shared Redis lock"
            result["redisLockReleased"] = True
        (root / "target/chain-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False))
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
