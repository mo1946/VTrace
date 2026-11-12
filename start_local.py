"""Start the local Python API (including analysis) and Vue without Docker."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent
LOGS = ROOT / 'data' / 'local-dev'


def ready(url: str) -> bool:
    try:
        with urlopen(url, timeout=3) as response:
            return response.status == 200
    except (URLError, OSError):
        return False


def start(name: str, command: list[str], directory: Path, url: str, *, environment: dict[str, str] | None = None) -> None:
    if ready(url):
        print(f'{name}: already running', flush=True)
        return
    LOGS.mkdir(parents=True, exist_ok=True)
    error_log = LOGS / f'{name}.err.log'
    with (LOGS / f'{name}.log').open('ab') as output, error_log.open('ab') as errors:
        process = subprocess.Popen(
            command, cwd=directory, stdout=output, stderr=errors,
            stdin=subprocess.DEVNULL,
            env=environment,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
        )
    for _ in range(30):
        if ready(url):
            print(f'{name}: ready (PID {process.pid})', flush=True)
            return
        if process.poll() is not None:
            break
        time.sleep(1)
    if process.poll() is None:
        process.terminate()
    raise RuntimeError(f'{name} failed to start. See {error_log}')


def main() -> None:
    node = shutil.which('node')
    vite = ROOT / 'client' / 'node_modules' / 'vite' / 'bin' / 'vite.js'
    if not node or not vite.is_file():
        raise RuntimeError('Install Node.js and run npm.cmd ci inside client first.')
    start('api', [sys.executable, '-m', 'uvicorn', 'seeit.main:app', '--host', '127.0.0.1', '--port', '9090'], ROOT / 'backend', 'http://127.0.0.1:9090/health')
    # This launcher starts no Java service: use the supported Python task routes.
    # Process-level Vite env takes precedence without changing deployment defaults.
    web_environment = {**os.environ, 'VITE_ANALYSIS_BACKEND': 'python'}
    start('web', [node, str(vite), '--host', '127.0.0.1', '--port', '5173', '--strictPort'], ROOT / 'client', 'http://127.0.0.1:5173/', environment=web_environment)
    print('VTrace: http://127.0.0.1:5173/')
    print('Local analysis uses Python. Configure AI/ASR/OCR services in .env for real video analysis.')
    print('If reusing an existing Vite server, set VITE_ANALYSIS_BACKEND=python in client/.env.local and restart Vite.')


if __name__ == '__main__':
    main()
