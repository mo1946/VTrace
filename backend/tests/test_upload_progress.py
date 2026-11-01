from __future__ import annotations

import asyncio
import os
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
import redis
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from seeit.upload_progress import UploadProgressStore


class MemoryRedis:
    """Small fault-injection double; the optional integration test uses real Redis."""

    def __init__(self):
        self.sets: dict[str, set[str]] = {}
        self.ttls: dict[str, int] = {}
        self.unavailable = False
        self.fail_writes = False
        self.lock = threading.RLock()

    def check(self):
        if self.unavailable:
            raise redis.ConnectionError("simulated outage")

    def smembers(self, key):
        self.check()
        with self.lock:
            return set(self.sets.get(key, set()))

    def sismember(self, key, value):
        self.check()
        with self.lock:
            return value in self.sets.get(key, set())

    def delete(self, key):
        self.check()
        with self.lock:
            self.sets.pop(key, None)
            self.ttls.pop(key, None)

    def pipeline(self, transaction=True):
        assert transaction
        owner = self

        class Pipeline:
            def __init__(self):
                self.commands = []

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def sadd(self, key, *values):
                self.commands.append(("sadd", key, values))

            def srem(self, key, *values):
                self.commands.append(("srem", key, values))

            def expire(self, key, ttl):
                self.commands.append(("expire", key, ttl))

            def execute(self):
                owner.check()
                if owner.fail_writes:
                    raise redis.ConnectionError("simulated write failure")
                with owner.lock:
                    for command, key, values in self.commands:
                        if command == "sadd":
                            owner.sets.setdefault(key, set()).update(values)
                        elif command == "srem":
                            owner.sets.setdefault(key, set()).difference_update(values)
                        else:
                            owner.ttls[key] = values

        return Pipeline()


@pytest.fixture
def upload_api(monkeypatch, tmp_path):
    import seeit.main as main

    engine = create_engine(f"sqlite:///{(tmp_path / 'upload.db').as_posix()}",
                           connect_args={"check_same_thread": False})
    main.Base.metadata.create_all(engine)
    monkeypatch.setattr(main, "engine", engine)
    monkeypatch.setattr(main, "SessionLocal", sessionmaker(bind=engine, expire_on_commit=False))
    monkeypatch.setattr(main, "UPLOAD_ROOT", tmp_path / "uploads")
    monkeypatch.setattr(main, "JWT_SECRET", "upload-test-only")
    monkeypatch.setattr(main, "redis_client", lambda: None)
    monkeypatch.setattr(main, "_local_rate_limits", {})
    monkeypatch.setenv("APP_ENV", "development")
    monkeypatch.setenv("VALIDATE_VIDEO_CONTENT", "false")
    cache = MemoryRedis()
    monkeypatch.setattr(main, "upload_progress", UploadProgressStore(lambda: cache, 86400))
    with TestClient(main.app) as client:
        def register(username):
            response = client.post("/user/register", json={"username": username, "password": "password123"})
            assert response.status_code == 200
            return {"Authorization": f"Bearer {response.json()['token']}"}

        yield main, client, cache, register("uploader"), register
    engine.dispose()


def initialize(client, headers, total=3):
    response = client.post("/media/init-upload", params={"filename": "clip.mp4", "totalChunks": total}, headers=headers)
    assert response.status_code == 200
    return response.json()


def send_chunk(client, headers, upload_id, index, content, total=3):
    return client.post("/media/upload-chunk", headers=headers,
                       data={"uploadId": upload_id, "chunkIndex": str(index), "totalChunks": str(total)},
                       files={"file": ("chunk", content)})


def test_redis_resume_duplicate_chunks_expiry_merge_and_dedup(upload_api):
    main, client, cache, headers, _ = upload_api
    upload_id = initialize(client, headers)
    key = main.upload_progress.key(upload_id)
    assert cache.ttls[key] == 86400
    for index, content in [(2, b"third"), (0, b"first-"), (0, b"first-")]:
        assert send_chunk(client, headers, upload_id, index, content).status_code == 200
    assert cache.sets[key] - {"__initialized__"} == {"0", "2"}
    assert client.get("/media/upload-status", params={"uploadId": upload_id}, headers=headers).json() == [0, 2]
    cache.delete(key)
    assert client.get("/media/upload-status", params={"uploadId": upload_id}, headers=headers).json() == [0, 2]

    # Expiry followed by another chunk must rebuild earlier completed chunks too.
    cache.delete(key)
    assert send_chunk(client, headers, upload_id, 1, b"second-").status_code == 200
    assert cache.sets[key] - {"__initialized__"} == {"0", "1", "2"}
    response = client.post("/media/complete-upload", params={"uploadId": upload_id}, headers=headers)
    assert response.status_code == 200
    media_id = response.json()["mediaId"]
    assert key not in cache.sets
    with main.SessionLocal() as db:
        assert Path(db.get(main.Media, media_id).file_path).read_bytes() == b"first-second-third"
        assert db.get(main.UploadSession, upload_id) is None

    duplicate_id = initialize(client, headers, 1)
    assert send_chunk(client, headers, duplicate_id, 0, b"first-second-third", 1).status_code == 200
    duplicate = client.post("/media/complete-upload", params={"uploadId": duplicate_id}, headers=headers)
    assert duplicate.json() == {"mediaId": media_id, "deduplicated": True}
    assert main.upload_progress.key(duplicate_id) not in cache.sets


def test_redis_outage_does_not_fail_successful_upload_or_merge(upload_api):
    main, client, cache, headers, _ = upload_api
    upload_id = initialize(client, headers)
    assert send_chunk(client, headers, upload_id, 0, b"a").status_code == 200
    cache.fail_writes = True
    assert send_chunk(client, headers, upload_id, 1, b"b").status_code == 200
    assert client.get("/media/upload-status", params={"uploadId": upload_id, "reconcile": True}, headers=headers).json() == [0, 1]
    cache.unavailable = True
    assert send_chunk(client, headers, upload_id, 2, b"c").status_code == 200
    assert client.get("/media/upload-status", params={"uploadId": upload_id}, headers=headers).json() == [0, 1, 2]
    completed = client.post("/media/complete-upload", params={"uploadId": upload_id}, headers=headers)
    assert completed.status_code == 200
    with main.SessionLocal() as db:
        assert Path(db.get(main.Media, completed.json()["mediaId"]).file_path).read_bytes() == b"abc"


def test_resume_reconciles_lost_redis_write_and_missing_disk_files(upload_api):
    main, client, cache, headers, _ = upload_api
    upload_id = initialize(client, headers)
    directory = main.chunk_dir(upload_id)
    key = main.upload_progress.key(upload_id)
    assert send_chunk(client, headers, upload_id, 0, b"a").status_code == 200
    (directory / "part-0").unlink()
    assert client.get("/media/upload-status", params={"uploadId": upload_id}, headers=headers).json() == []

    # Disk write succeeded, but Redis acknowledgement/update was lost.
    (directory / "part-1").write_bytes(b"b")
    (directory / "part-2.uploading").write_bytes(b"partial")
    (directory / "part-99").write_bytes(b"invalid")
    (directory / "part-2").mkdir()
    cache.sets[key].add("bad-value")
    restored = client.get("/media/upload-status", params={"uploadId": upload_id, "reconcile": True}, headers=headers)
    assert restored.json() == [1]
    assert cache.sets[key] == {"__initialized__", "1"}


def test_upload_ownership_and_disk_validation_cannot_be_bypassed_by_redis(upload_api):
    main, client, cache, headers, register = upload_api
    upload_id = initialize(client, headers)
    key = main.upload_progress.key(upload_id)
    cache.sets[key].update({"0", "1", "2"})
    other = register("intruder")
    assert client.get("/media/upload-status", params={"uploadId": upload_id}, headers=other).status_code == 404
    assert send_chunk(client, other, upload_id, 0, b"x").status_code == 404
    assert client.post("/media/complete-upload", params={"uploadId": upload_id}, headers=other).status_code == 404
    # Redis is not proof that the actual chunks are complete.
    assert client.post("/media/complete-upload", params={"uploadId": upload_id}, headers=headers).status_code == 400


def test_partial_final_chunk_size_limit_and_failed_chunk_cleanup(upload_api, monkeypatch):
    main, client, cache, headers, _ = upload_api
    monkeypatch.setattr(main, "MAX_CHUNK_BYTES", 4)
    monkeypatch.setattr(main, "MAX_UPLOAD_BYTES", 10)
    upload_id = initialize(client, headers)
    oversized = send_chunk(client, headers, upload_id, 0, b"12345")
    assert oversized.status_code == 413
    assert not list(main.chunk_dir(upload_id).iterdir())
    assert cache.sets[main.upload_progress.key(upload_id)] == {"__initialized__"}
    for index, content in enumerate([b"1234", b"5678", b"90"]):
        assert send_chunk(client, headers, upload_id, index, content).status_code == 200
    assert client.post("/media/complete-upload", params={"uploadId": upload_id}, headers=headers).status_code == 200

    too_large_id = initialize(client, headers)
    for index, content in enumerate([b"1234", b"5678", b"901"]):
        assert send_chunk(client, headers, too_large_id, index, content).status_code == 200
    assert client.post("/media/complete-upload", params={"uploadId": too_large_id}, headers=headers).status_code == 413


def test_interrupted_chunk_cleans_temporary_file_without_recording_progress(upload_api):
    main, client, cache, headers, _ = upload_api
    upload_id = initialize(client, headers, 1)

    class InterruptedFile:
        calls = 0

        async def read(self, size):
            self.calls += 1
            if self.calls == 1:
                return b"partial"
            raise ConnectionError("client disconnected")

    with main.SessionLocal() as db:
        user = db.scalar(main.select(main.User).where(main.User.username == "uploader"))
    with pytest.raises(ConnectionError):
        asyncio.run(main.upload_chunk(uploadId=upload_id, chunkIndex=0, totalChunks=1,
                                      file=InterruptedFile(), user=user))
    assert not list(main.chunk_dir(upload_id).iterdir())
    assert cache.sets[main.upload_progress.key(upload_id)] == {"__initialized__"}


def test_concurrent_retries_use_separate_temporary_files(upload_api):
    main, client, cache, headers, _ = upload_api
    upload_id = initialize(client, headers, 1)

    class SlowFile:
        calls = 0

        async def read(self, size):
            await asyncio.sleep(0)
            self.calls += 1
            return b"same-complete-chunk" if self.calls == 1 else b""

    with main.SessionLocal() as db:
        user = db.scalar(main.select(main.User).where(main.User.username == "uploader"))

    async def retries():
        return await asyncio.gather(*[
            main.upload_chunk(uploadId=upload_id, chunkIndex=0, totalChunks=1, file=SlowFile(), user=user)
            for _ in range(2)
        ])

    assert len(asyncio.run(retries())) == 2
    directory = main.chunk_dir(upload_id)
    assert (directory / "part-0").read_bytes() == b"same-complete-chunk"
    assert list(directory.iterdir()) == [directory / "part-0"]
    assert cache.sets[main.upload_progress.key(upload_id)] == {"__initialized__", "0"}


def test_stale_upload_and_invalid_video_clear_redis_progress(upload_api, monkeypatch):
    main, client, cache, headers, _ = upload_api
    upload_id = initialize(client, headers, 1)
    assert send_chunk(client, headers, upload_id, 0, b"not-a-video", 1).status_code == 200
    monkeypatch.setenv("VALIDATE_VIDEO_CONTENT", "true")

    def invalid_video(path):
        raise ValueError("invalid video")

    monkeypatch.setattr(main, "validate_video_file", invalid_video)
    assert client.post("/media/complete-upload", params={"uploadId": upload_id}, headers=headers).status_code == 400
    assert main.upload_progress.key(upload_id) not in cache.sets
    stale_id = initialize(client, headers, 1)
    with main.SessionLocal() as db:
        db.get(main.UploadSession, stale_id).created_at = datetime.now(timezone.utc) - timedelta(days=2)
        db.commit()
    main.cleanup_stale_uploads()
    assert main.upload_progress.key(stale_id) not in cache.sets
    assert not main.chunk_dir(stale_id).exists()


@pytest.mark.skipif(not os.getenv("VTRACE_TEST_REDIS_URL"), reason="Requires an isolated test Redis URL")
def test_real_redis_upload_progress_roundtrip(tmp_path):
    client = redis.Redis.from_url(os.environ["VTRACE_TEST_REDIS_URL"], decode_responses=True)
    store = UploadProgressStore(lambda: client, 60)
    upload_id = f"test-{uuid.uuid4()}"
    try:
        store.initialize(upload_id)
        (tmp_path / "part-0").write_bytes(b"chunk")
        store.record(upload_id, 0, tmp_path, 1)
        assert store.read(upload_id, tmp_path, 1) == [0]
        assert 0 < client.ttl(store.key(upload_id)) <= 60
        client.delete(store.key(upload_id))
        assert store.read(upload_id, tmp_path, 1) == [0]
    finally:
        store.clear(upload_id)
        client.close()
