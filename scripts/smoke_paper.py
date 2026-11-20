#!/usr/bin/env python3
"""Black-box smoke test for the shaded plugin on a real Paper server.

This verifies loading, the console command dispatcher, local storage and a
background backup. It does not certify third-party Bukkit bridges such as Cardboard.
"""
from __future__ import annotations

import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

STARTUP_TIMEOUT = 300


def main() -> int:
    server_jar = Path(sys.argv[1]).resolve()
    server_dir = Path(sys.argv[2]).resolve()
    server_dir.mkdir(parents=True, exist_ok=True)

    proc = subprocess.Popen(
        ["java", "-Xms512M", "-Xmx2G", "-jar", str(server_jar), "nogui"],
        cwd=server_dir,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
        bufsize=1,
    )
    output: queue.Queue[str | None] = queue.Queue()

    def read_console() -> None:
        assert proc.stdout is not None
        with (server_dir / "smoke-console.log").open("w", encoding="utf-8") as logfile:
            for line in proc.stdout:
                print(line, end="", flush=True)
                logfile.write(line)
                logfile.flush()
                output.put(line)
        output.put(None)

    threading.Thread(target=read_console, daemon=True).start()
    deadline = time.monotonic() + STARTUP_TIMEOUT
    enabled = ready = status = backup = stopped = False

    try:
        while time.monotonic() < deadline:
            try:
                line = output.get(timeout=1)
            except queue.Empty:
                if proc.poll() is not None:
                    break
                continue
            if line is None:
                break

            if "Enabling CloverRep v" in line:
                enabled = True
            if "Done (" in line and not ready:
                ready = True
                assert proc.stdin is not None
                proc.stdin.write("cloverrep status\ncloverrep backup\n")
                proc.stdin.flush()
            if "Backend:local" in line:
                status = True
            if "Backup created:" in line:
                backup = True
            if ready and status and backup and not stopped:
                stopped = True
                assert proc.stdin is not None
                proc.stdin.write("stop\n")
                proc.stdin.flush()

        if proc.poll() is None:
            proc.terminate()
        try:
            exit_code = proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            exit_code = proc.wait(timeout=10)

        backups = list((server_dir / "plugins" / "CloverRep" / "backups").glob("*"))
        passed = enabled and ready and status and backup and stopped and exit_code == 0 and bool(backups)
        print(
            "SMOKE RESULT:",
            {"plugin_enabled": enabled, "server_ready": ready, "status": status,
             "backup": backup, "backup_files": len(backups), "clean_shutdown": exit_code == 0},
            flush=True,
        )
        return 0 if passed else 1
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=10)


if __name__ == "__main__":
    raise SystemExit(main())
