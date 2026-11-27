from __future__ import annotations

import runpy
import sys


def deny_network(event: str, args: tuple) -> None:
    if event in {"socket.connect", "socket.getaddrinfo", "socket.sendto"}:
        raise RuntimeError("Media worker network access is disabled; provision all reviewed assets locally")


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] != "scripts.inference":
        raise ValueError("Only the reviewed MuseTalk inference module is allowed")
    sys.addaudithook(deny_network)
    sys.argv = [sys.argv[1], *sys.argv[2:]]
    runpy.run_module("scripts.inference", run_name="__main__")


if __name__ == "__main__":
    main()
