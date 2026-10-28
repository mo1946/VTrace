"""Seed an isolated MySQL test database using VTrace's existing migrations and ORM.

Requires the existing backend Python dependencies. Never use the business database.
"""
from __future__ import annotations

import os
import json
import hashlib
from pathlib import Path
import subprocess
import sys
from datetime import datetime

from sqlalchemy import create_engine
from sqlalchemy.engine import make_url


def main() -> None:
    raw_url = os.environ["VTRACE_TEST_PYTHON_DB_URL"]
    url = make_url(raw_url)
    if url.drivername != "mysql+pymysql" or not (url.database or "").startswith("vtrace_business_test_"):
        raise SystemExit("Only mysql+pymysql databases named vtrace_business_test_* are allowed")

    # Create only the explicitly named isolated database. Do not drop any database/table.
    database = url.database
    if not database or not all(char.isascii() and (char.isalnum() or char == "_") for char in database):
        raise SystemExit("Invalid test database name")
    server = create_engine(url._replace(database=None), isolation_level="AUTOCOMMIT")
    try:
        with server.connect() as connection:
            connection.exec_driver_sql(f"CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4")
    finally:
        server.dispose()

    backend = Path(__file__).resolve().parents[2] / "backend"
    test_secret = os.environ.get("VTRACE_TEST_JWT_SECRET", "vtrace-stage2-test-secret-at-least-32-bytes")
    environment = {**os.environ, "DATABASE_URL": raw_url, "AUTO_CREATE_SCHEMA": "false", "JWT_SECRET": test_secret}
    subprocess.run([sys.executable, "-m", "alembic", "upgrade", "head"],
                   cwd=backend, env=environment, check=True)

    os.environ["DATABASE_URL"] = raw_url
    os.environ["AUTO_CREATE_SCHEMA"] = "false"
    os.environ["JWT_SECRET"] = test_secret
    sys.path.insert(0, str(backend))
    from seeit.main import AnalysisTask, EvidenceSegment, Media, SessionLocal, User, engine, issue_token

    try:
        with SessionLocal() as session:
            # Refuse to replace existing fixtures: reruns should use a fresh test database.
            if session.get(AnalysisTask, "00000000-0000-4000-8000-000000000001"):
                raise SystemExit("Fixture already exists; select a fresh vtrace_business_test_* database")
            user = User(username="business_fixture", password_hash="test-only-not-a-login-hash")
            session.add(user)
            session.flush()
            media = Media(user_id=user.id, filename="Python创建的视频.mp4",
                          file_path="/test-only/not-a-real-video.mp4", content_hash="b" * 32)
            session.add(media)
            session.flush()
            other_user = User(username="business_other", password_hash="test-only")
            disabled_user = User(username="business_disabled", password_hash="test-only", is_active=False)
            session.add_all([other_user, disabled_user])
            session.flush()
            other_media = Media(user_id=other_user.id, filename="other.mp4",
                                file_path="/test-only/other.mp4", content_hash="c" * 32)
            session.add(other_media)
            session.add_all([
                EvidenceSegment(media_id=media.id, source="ASR", start_ms=1000, end_ms=12000,
                                content="本节介绍视频证据问答平台，通过消息队列异步处理任务，并引用时间戳证据。"),
                EvidenceSegment(media_id=media.id, source="ASR", start_ms=15000, end_ms=25000,
                                content="系统使用数据库保存任务状态，Redis 防止重复提交，Worker 生成分析报告。"),
            ])
            session.flush()
            for number, state, attempts in [
                (1, "QUEUED", 0), (2, "PROCESSING", 1), (3, "RETRYING", 2),
                (4, "COMPLETED", 1), (5, "FAILED", 3),
            ]:
                session.add(AnalysisTask(
                    id=f"00000000-0000-4000-8000-{number:012d}",
                    media_id=media.id, user_id=user.id, goal=f"Python 测试目标 {number}",
                    goal_hash=f"{number:064x}", state=state, attempt_count=attempts,
                    stage="OCR" if number == 2 else None,
                    progress_current=3 if number == 2 else 0,
                    progress_total=8 if number == 2 else 0,
                    progress_message="正在识别关键帧（3/8 帧）" if number == 2 else None,
                    error="RuntimeError: 模型暂时不可用" if number == 5 else None,
                    result="# Python 报告\n\n带时间戳的证据总结。" if number == 4 else None,
                    created_at=datetime(2026, 7, 21, 10, 20, 30),
                    updated_at=datetime(2026, 7, 21, 10, 21, 30),
                ))
            session.commit()
            fixture = {
                "userId": user.id, "mediaId": media.id, "token": issue_token(user),
                "otherUserId": other_user.id, "otherMediaId": other_media.id,
                "otherToken": issue_token(other_user), "disabledToken": issue_token(disabled_user),
                "hashExample": hashlib.sha256("总结视频".encode("utf-8")).hexdigest(),
            }
        fixture_path = Path(os.environ.get("VTRACE_TEST_FIXTURE_FILE", str(
            Path(__file__).resolve().parents[1] / "target" / "python-fixture.json")))
        fixture_path.parent.mkdir(parents=True, exist_ok=True)
        fixture_path.write_text(json.dumps(fixture, ensure_ascii=False), encoding="utf-8")
        print(f"Seeded 5 tasks through the existing Python AnalysisTask ORM into {database}")
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
