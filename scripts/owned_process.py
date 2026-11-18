"""Bounded shutdown for a Popen child owned by the caller, without serial-based console commands."""

import subprocess


def stop_owned_child(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        process.wait(timeout=5)
        return
    try:
        process.terminate()
    except ProcessLookupError:
        pass
    finally:
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
