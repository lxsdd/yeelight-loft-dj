#!/usr/bin/env python3
"""Synthetic multi-client Color4 classic-LAN quota pressure, without I/O.

This intentionally models one of several *possible* rate accounting policies.
It is NOT extracted Color4 firmware or a predictor of actual hardware behavior.
No sockets, subprocesses, sleeps, raw captures, secrets or network endpoints.
All identities are short arbitrary labels; only ordinal counters are reported.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
import re

# Limits documented for classic Yeelight LAN, not established for this image.
MODEL_CONNECTIONS = 4
MODEL_PER_CONNECTION = 60
MODEL_GLOBAL = 144
WINDOW_MS = 60_000

# Conservative proposed application policy from isolated product Draft #31.
LOCAL_BUDGET = 24
LOCAL_SPACING_MS = 1_500
MAX_TIME_MS = (1 << 63) - 1
LABEL = re.compile(r"^[a-z][a-z0-9_-]{0,31}$")
CLASSIC_METHODS = frozenset({
    "get_prop", "set_rgb", "set_bright", "set_power", "set_ct_abx",
    "set_hsv", "set_default", "start_cf", "stop_cf", "set_music",
})


def _time(t: int) -> None:
    if type(t) is not int or not 0 <= t <= MAX_TIME_MS:
        raise ValueError("invalid synthetic monotonic clock")


def _label(value: str) -> None:
    if not isinstance(value, str) or not LABEL.fullmatch(value):
        raise ValueError("synthetic opaque client label required")


def _trim(queue: deque[int], now_ms: int) -> None:
    while queue and queue[0] <= now_ms - WINDOW_MS:
        queue.popleft()


@dataclass
class _Connection:
    owner: str
    attempts: deque[int] = field(default_factory=deque)


@dataclass(frozen=True)
class OfflineResult:
    """No IP, token, payload, or original image content leaves the model."""
    status: str
    host_terminal: bool
    model_global_attempts: int
    model_connection_attempts: int
    host_attempts: int


class Color4MultiClientQuotaOracle:
    """Explicit-time, per-device in-memory counterfactual.

    A single object stands for one synthetic device. Local budget is shared
    by ALL its own-app classic TCP connections. Other clients can exhaust a
    modeled global device budget even when our local budget is respected.
    Observed quota/timeout feedback is injected as synthetic input; neither
    is assumed to occur at any particular real firmware threshold.
    """

    def __init__(self, start_ms: int = 0):
        _time(start_ms)
        self._clock = start_ms
        self._connections: dict[str, _Connection] = {}
        self._global: deque[int] = deque()
        self._local: deque[int] = deque()
        self._last_local: int | None = None
        self._local_stop: str | None = None
        # Injected hypothetical device fault. Never infer this from quota
        # counters, power-on hours or actual traffic.
        self._injected_lan_stall = False

    def inject_lan_service_stall(self, at_ms: int) -> None:
        """Simulated sticky LAN-service outage; not a firmware diagnosis."""
        self._advance(at_ms)
        self._injected_lan_stall = True

    def simulate_app_force_stop(self, at_ms: int) -> int:
        """Closes model local app sockets, preserving device firmware state."""
        self._advance(at_ms)
        owned = [name for name, c in self._connections.items()
                 if c.owner == "local"]
        for name in owned:
            del self._connections[name]
        return len(owned)

    def simulate_power_cycle(self, at_ms: int) -> None:
        """Clears volatile model device state; host quarantine survives."""
        self._advance(at_ms)
        self._connections.clear()
        self._global.clear()
        self._injected_lan_stall = False
        # The host journal, last attempt and rolling budget deliberately
        # survive; no automatic local unlock or probe is simulated.

    def modeled_cloud_path(self, at_ms: int) -> str:
        """Assumed independent cloud route, never an actual device response."""
        self._advance(at_ms)
        return "MODELED_CLOUD_ROUTE_INDEPENDENT_OF_LAN"

    def _advance(self, at_ms: int) -> None:
        _time(at_ms)
        if at_ms < self._clock:
            raise ValueError("synthetic clock moved backwards")
        self._clock = at_ms
        _trim(self._global, at_ms)
        _trim(self._local, at_ms)
        for connection in self._connections.values():
            _trim(connection.attempts, at_ms)

    def open(self, client: str, owner: str, at_ms: int) -> str:
        """No implicit reconnect, quota reset, or real TCP connection."""
        _label(client)
        if owner not in ("local", "external"):
            raise ValueError("owner must be local or external")
        self._advance(at_ms)
        if client in self._connections:
            return "DUPLICATE_SESSION_REFUSED"
        if len(self._connections) >= MODEL_CONNECTIONS:
            return "MODELED_CONNECTION_CAP"
        self._connections[client] = _Connection(owner)
        return "MODELED_OPEN"

    def close(self, client: str, at_ms: int) -> bool:
        _label(client)
        self._advance(at_ms)
        return self._connections.pop(client, None) is not None

    def command(
        self, client: str, method: str, at_ms: int, *,
        synthetic_reply: str = "ok",
    ) -> OfflineResult:
        """One potential command ATTEMPT, never a simulated retransmission.

        'quota' is an injected report of a firmware rejection, 'timeout' an
        ambiguous sent command. Model thresholds may differ from real ones.
        Neither a timeout nor a rejection permits another local attempt.
        """
        _label(client)
        if method not in CLASSIC_METHODS:
            # In particular reverse TCP music frames and Chroma UDP cannot
            # be silently reclassified as ordinary classic-LAN commands.
            raise ValueError("not an explicitly modeled classic command")
        if synthetic_reply not in ("ok", "quota", "timeout"):
            raise ValueError("invalid injected response")
        self._advance(at_ms)
        conn = self._connections.get(client)

        def result(status: str) -> OfflineResult:
            return OfflineResult(
                status=status,
                host_terminal=self._local_stop is not None,
                model_global_attempts=len(self._global),
                model_connection_attempts=len(conn.attempts) if conn else 0,
                host_attempts=len(self._local),
            )

        if conn is None:
            return result("NO_CONNECTION")
        if conn.owner == "local":
            if self._local_stop:
                return result("LOCAL_FAIL_CLOSED")
            if len(self._local) >= LOCAL_BUDGET:
                return result("LOCAL_BUDGET_DEFER")
            if self._last_local is not None and at_ms - self._last_local < LOCAL_SPACING_MS:
                return result("LOCAL_PACE_DEFER")
            # Count possible wire attempts BEFORE analyzing replies or errors.
            # Reconnecting on a new local TCP session cannot reset this budget.
            self._local.append(at_ms)
            self._last_local = at_ms

        if self._injected_lan_stall:
            # Do NOT fabricate a real client quota exceeded error. Several
            # device-side causes could look identical to Yeelight Classic.
            if conn.owner == "local":
                self._local_stop = "INJECTED_UNRESPONSIVE_DEVICE"
            return result("INJECTED_LAN_SERVICE_UNAVAILABLE")
        if len(conn.attempts) >= MODEL_PER_CONNECTION:
            if conn.owner == "local":
                self._local_stop = "MODEL_LIMIT"
            return result("MODELED_CONNECTION_QUOTA")
        if len(self._global) >= MODEL_GLOBAL:
            if conn.owner == "local":
                self._local_stop = "MODEL_LIMIT"
            return result("MODELED_GLOBAL_QUOTA")
        if synthetic_reply == "quota":
            if conn.owner == "local":
                self._local_stop = "REPORTED_QUOTA"
            return result("INJECTED_QUOTA_REJECTION")

        # Timeout is ambiguous: the device may already have executed the
        # command. Charge the synthetic device's model too, never replay.
        conn.attempts.append(at_ms)
        self._global.append(at_ms)
        if synthetic_reply == "timeout":
            if conn.owner == "local":
                self._local_stop = "UNCERTAIN_SEND"
            return result("INJECTED_AMBIGUOUS_TIMEOUT")
        return result("MODELED_ACCEPTED_NOT_REAL_ACK")

    def snapshot(self, at_ms: int) -> dict[str, int | bool]:
        self._advance(at_ms)
        return {
            "connected_sessions": len(self._connections),
            "modeled_global_attempts": len(self._global),
            "host_attempts": len(self._local),
            "host_terminal": self._local_stop is not None,
            "injected_lan_service_stalled": self._injected_lan_stall,
            "hardware_qualified": False,
            "firmware_root_cause_proven": False,
        }


def demonstrate() -> dict[str, object]:
    """No real client labels, addresses or firmware identifiers in output."""
    device = Color4MultiClientQuotaOracle()
    for label in ("other-a", "other-b", "other-c"):
        assert device.open(label, "external", 0) == "MODELED_OPEN"
    for label, count in (("other-a", 60), ("other-b", 60), ("other-c", 24)):
        for _ in range(count):
            assert device.command(label, "get_prop", 0).status == "MODELED_ACCEPTED_NOT_REAL_ACK"
    assert device.open("ours", "local", 0) == "MODELED_OPEN"
    blocked = device.command("ours", "set_rgb", 0)
    return {
        "synthetic_scenario": "3 external connections + 1 local connection",
        "other_client_attempts": 144,
        "our_app_used_only_one_attempt": True,
        "modeled_result": blocked.status,
        "host_terminal": blocked.host_terminal,
        "root_cause_proven": False,
        "hardware_qualified": False,
    }


if __name__ == "__main__":
    import json
    print(json.dumps(demonstrate(), sort_keys=True, indent=2))
