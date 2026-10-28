"""Local ASR fixture for isolated integration tests; never part of runtime services.

Tests exercise real upload/audio extraction/Worker code with deterministic ASR responses.
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock


class Handler(BaseHTTPRequestHandler):
    failing = False
    requests = 0
    lock = Lock()

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path in {"/fixture/fail", "/fixture/reset"}:
            with self.lock:
                Handler.failing = self.path.endswith("fail")
            self.reply(200, {"failing": Handler.failing})
        elif self.path == "/audio/transcriptions":
            with self.lock:
                Handler.requests += 1
                failing = Handler.failing
            if failing:
                self.reply(503, {"error": "Injected ASR outage for regression"})
            else:
                self.reply(200, {"segments": [
                    {"start": 0.0, "end": 0.5, "text": "本视频介绍消息队列异步处理视频分析任务。"},
                    {"start": 0.5, "end": 1.0, "text": "系统通过 MySQL 保存状态，Redis 防止重复任务，并引用时间戳证据生成报告。"},
                ]})
        else:
            self.reply(404, {"error": "Unknown fixture endpoint"})

    def do_GET(self):
        self.reply(200, {"requests": Handler.requests, "failing": Handler.failing})

    def reply(self, status, body):
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=20991)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
