"""Redis upload progress is a recoverable index; chunk files remain authoritative."""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Callable

import redis


log = logging.getLogger("seeit.upload")
_READY = "__initialized__"
_PART_NAME = re.compile(r"part-(\d+)\Z")


def scan_uploaded_chunks(directory: Path, total_chunks: int) -> list[int]:
    indices: set[int] = set()
    for path in directory.glob("part-*"):
        match = _PART_NAME.fullmatch(path.name)
        if match and path.is_file():
            index = int(match.group(1))
            if 0 <= index < total_chunks:
                indices.add(index)
    return sorted(indices)


class UploadProgressStore:
    def __init__(self, get_client: Callable[[], redis.Redis | None], ttl_seconds: int):
        self.get_client = get_client
        self.ttl_seconds = max(1, ttl_seconds)

    @staticmethod
    def key(upload_id: str) -> str:
        return f"upload:{upload_id}:chunks"

    @staticmethod
    def _members(client: redis.Redis, key: str) -> set[str]:
        return {
            value.decode("utf-8", errors="replace") if isinstance(value, bytes) else str(value)
            for value in client.smembers(key)
        }

    def _write(self, client: redis.Redis, key: str, indices: list[int], remove: set[str] | None = None) -> None:
        # Merge instead of deleting/replacing the set: concurrent chunk completions
        # must not be erased by a slower disk reconciliation. MULTI also couples TTL.
        with client.pipeline(transaction=True) as pipeline:
            if remove:
                pipeline.srem(key, *remove)
            pipeline.sadd(key, _READY, *(str(index) for index in indices))
            pipeline.expire(key, self.ttl_seconds)
            pipeline.execute()

    def initialize(self, upload_id: str) -> None:
        client = self.get_client()
        if client is not None:
            try:
                self._write(client, self.key(upload_id), [])
            except redis.RedisError:
                log.warning("upload_progress_init_unavailable upload_id=%s", upload_id)

    def record(self, upload_id: str, index: int, directory: Path, total_chunks: int) -> None:
        """Called only after the complete chunk has been atomically published."""
        client = self.get_client()
        if client is None:
            return
        try:
            key = self.key(upload_id)
            # If Redis expired/restarted, recover earlier chunks before marking ready.
            indices = [index] if client.sismember(key, _READY) else scan_uploaded_chunks(directory, total_chunks)
            self._write(client, key, indices)
        except redis.RedisError:
            # A successful disk write must remain successful during a Redis outage.
            log.warning("upload_progress_write_unavailable upload_id=%s", upload_id)

    def read(self, upload_id: str, directory: Path, total_chunks: int, *, reconcile: bool = False) -> list[int]:
        client = self.get_client()
        key = self.key(upload_id)
        members: set[str] = set()
        if client is not None:
            try:
                members = self._members(client, key)
                cached = sorted({int(value) for value in members if value.isascii() and value.isdigit()
                                 and 0 <= int(value) < total_chunks})
                valid = {_READY, *(str(index) for index in cached)}
                if (_READY in members and members == valid and not reconcile
                        and all((directory / f"part-{index}").is_file() for index in cached)):
                    return cached
            except redis.RedisError:
                log.warning("upload_progress_read_unavailable upload_id=%s", upload_id)
                client = None

        indices = scan_uploaded_chunks(directory, total_chunks)
        if client is not None:
            try:
                valid = {_READY, *(str(index) for index in indices)}
                self._write(client, key, indices, members - valid)
            except redis.RedisError:
                log.warning("upload_progress_rebuild_unavailable upload_id=%s", upload_id)
        return indices

    def clear(self, upload_id: str) -> None:
        client = self.get_client()
        if client is not None:
            try:
                client.delete(self.key(upload_id))
            except redis.RedisError:
                log.warning("upload_progress_cleanup_unavailable upload_id=%s", upload_id)
