#!/usr/bin/env python3
"""Offline reference model for Yeelight LAN quota timing around uint32 uptime wrap.

NOT Yeelight OEM firmware or a port of disassembled code. No TCP, UDP, OTA,
networking, device access, file writes or instruction to change any lamp.
A safe timer algorithm is contrasted with a *hypothetical* non-rearming one.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict

UINT32_WRAP_MS = 1 << 32
REFILL_INTERVAL_MS = 60_000
MAX_CLIENT_CONNECTIONS = 4
PER_CONNECTION_QUOTA = 60
GLOBAL_QUOTA = 144


@dataclass
class SafeRefillBucket:
    """A reference fixed-window quota with unbounded/64-bit monotonic clock.

    The fixed-window policy is ONLY a reproducible model, NOT a claim of
    matching exact OEM rate accounting. Command pacing is a separate concern.
    """
    capacity: int
    started_at_ms: int
    interval_ms: int = REFILL_INTERVAL_MS
    tokens: int = field(init=False)
    last_window_ms: int = field(init=False)
    last_seen_ms: int = field(init=False)

    def __post_init__(self) -> None:
        if self.capacity <= 0 or self.interval_ms <= 0 or self.started_at_ms < 0:
            raise ValueError("invalid synthetic quota clock/policy")
        self.tokens = self.capacity
        self.last_window_ms = self.started_at_ms
        self.last_seen_ms = self.started_at_ms

    def refresh(self, now_ms: int) -> None:
        if not isinstance(now_ms, int) or now_ms < self.last_seen_ms or now_ms < 0:
            raise ValueError("clock must be monotonic unsigned milliseconds")
        self.last_seen_ms = now_ms
        elapsed = now_ms - self.last_window_ms
        if elapsed >= self.interval_ms:
            # Advance complete windows, so idle gaps of 49.7 days, two wraps
            # or much longer never strand the timer or accumulate tokens.
            self.last_window_ms += (elapsed // self.interval_ms) * self.interval_ms
            self.tokens = self.capacity

    def take(self) -> bool:
        if self.tokens < 1:
            return False
        self.tokens -= 1
        return True


@dataclass
class SafeLanQuotaReference:
    """Atomic global plus per-connection quota reference; no actual lamps."""
    started_at_ms: int = 0
    global_bucket: SafeRefillBucket = field(init=False)
    sessions: Dict[str, SafeRefillBucket] = field(default_factory=dict)
    most_recent_ms: int = field(init=False)

    def __post_init__(self) -> None:
        self.global_bucket = SafeRefillBucket(GLOBAL_QUOTA, self.started_at_ms)
        self.most_recent_ms = self.started_at_ms

    def _advance(self, now_ms: int) -> None:
        if not isinstance(now_ms, int) or now_ms < self.most_recent_ms:
            raise ValueError("clock moved backwards")
        self.most_recent_ms = now_ms

    def open_session(self, key: str, now_ms: int) -> bool:
        self._advance(now_ms)
        if not key or key in self.sessions or len(self.sessions) >= MAX_CLIENT_CONNECTIONS:
            return False
        self.sessions[key] = SafeRefillBucket(PER_CONNECTION_QUOTA, now_ms)
        return True

    def close_session(self, key: str) -> None:
        self.sessions.pop(key, None)

    def request(self, key: str, now_ms: int) -> bool:
        """Every request incl. get_prop counts; never bypass hardware budget."""
        self._advance(now_ms)
        own = self.sessions.get(key)
        if own is None:
            return False
        own.refresh(now_ms)
        self.global_bucket.refresh(now_ms)
        if own.tokens <= 0 or self.global_bucket.tokens <= 0:
            return False
        return own.take() and self.global_bucket.take()


class HypotheticalStalledRefill:
    """EXPLANATORY NEGATIVE MODEL, not confirmed color4 implementation.

    Mimics the *effect* of the OEM-reported timer failing to rearm at
    UINT32_WRAP_MS, for regression contrast against SafeRefillBucket.
    """
    def __init__(self) -> None:
        self.bucket = SafeRefillBucket(PER_CONNECTION_QUOTA, 0)
        self.timer_armed = True

    def request(self, now_ms: int) -> bool:
        if now_ms >= UINT32_WRAP_MS:
            self.timer_armed = False
        if self.timer_armed:
            self.bucket.refresh(now_ms)
        return self.bucket.take()
