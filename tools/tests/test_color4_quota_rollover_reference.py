"""Synthetic-only reference tests for the suspected 49.7-day refill bug."""
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from color4_quota_rollover_reference import (
    GLOBAL_QUOTA, PER_CONNECTION_QUOTA, UINT32_WRAP_MS,
    HypotheticalStalledRefill, SafeLanQuotaReference, SafeRefillBucket,
)


class QuotaRolloverReferenceTest(unittest.TestCase):
    def test_exact_32_bit_millisecond_boundary(self):
        # 49 days + 17 hours + 2 minutes + 47.296 seconds.
        self.assertEqual(UINT32_WRAP_MS, 4_294_967_296)
        self.assertEqual(divmod(UINT32_WRAP_MS, 86_400_000)[0], 49)

    def test_bucket_before_and_after_wrap_in_same_minute(self):
        start = UINT32_WRAP_MS - 15_000
        b = SafeRefillBucket(3, start)
        self.assertTrue(b.take())
        self.assertTrue(b.take())
        self.assertTrue(b.take())
        self.assertFalse(b.take())
        b.refresh(UINT32_WRAP_MS + 5_000)
        self.assertEqual(b.tokens, 0)
        b.refresh(UINT32_WRAP_MS + 45_000)
        self.assertEqual(b.tokens, 3)

    def test_bucket_recovers_after_longsilence_starting_at_boot(self):
        b = SafeRefillBucket(1, 0)
        self.assertTrue(b.take())
        b.refresh(UINT32_WRAP_MS + 500_000)
        self.assertEqual(b.tokens, 1)
        self.assertTrue(b.take())

    def test_multiple_wraps_recover_without_overaccumulation(self):
        b = SafeRefillBucket(2, 0)
        for n in (1, 2, 3, 12):
            b.refresh(n * UINT32_WRAP_MS + 60_001)
            self.assertEqual(b.tokens, 2)
            self.assertTrue(b.take())
        self.assertEqual(b.tokens, 1)

    def test_all_limits_preserved_over_boundary(self):
        now = UINT32_WRAP_MS - 61_000
        ref = SafeLanQuotaReference(now)
        for s in ("a", "b", "c", "d"):
            self.assertTrue(ref.open_session(s, now))
        self.assertFalse(ref.open_session("e", now))
        for s in ("a", "b"):
            self.assertEqual(sum(ref.request(s, now) for _ in range(60)), 60)
            self.assertFalse(ref.request(s, now))
        self.assertEqual(sum(ref.request("c", now) for _ in range(60)), 24)
        self.assertFalse(ref.request("d", now))
        # In a later minute, precisely across 2^32ms, limits refill,
        # not expand: same 60 per session and 144 across sessions.
        after = UINT32_WRAP_MS + 1
        successes = [
            sum(ref.request(s, after) for _ in range(100))
            for s in ("a", "b", "c", "d")
        ]
        self.assertEqual(successes, [60, 60, 24, 0])
        self.assertEqual(sum(successes), GLOBAL_QUOTA)

    def test_rejected_commands_do_not_consume_other_counter(self):
        ref = SafeLanQuotaReference()
        self.assertTrue(ref.open_session("a", 0))
        for _ in range(PER_CONNECTION_QUOTA):
            self.assertTrue(ref.request("a", 0))
        remaining = ref.global_bucket.tokens
        self.assertFalse(ref.request("a", 0))
        self.assertEqual(ref.global_bucket.tokens, remaining)

    def test_opening_new_connection_does_not_reset_global_quota(self):
        ref = SafeLanQuotaReference()
        self.assertTrue(ref.open_session("a", 0))
        for _ in range(60):
            self.assertTrue(ref.request("a", 0))
        ref.close_session("a")
        self.assertTrue(ref.open_session("b", 0))
        self.assertEqual(ref.global_bucket.tokens, GLOBAL_QUOTA - 60)

    def test_new_connection_after_long_idle_can_operate(self):
        ref = SafeLanQuotaReference()
        self.assertTrue(ref.open_session("a", 0))
        for _ in range(60):
            self.assertTrue(ref.request("a", 0))
        ref.close_session("a")
        self.assertTrue(ref.open_session("b", UINT32_WRAP_MS + 1))
        self.assertTrue(ref.request("b", UINT32_WRAP_MS + 1))

    def test_clock_reversal_is_rejected_without_underflow(self):
        ref = SafeLanQuotaReference(120000)
        self.assertTrue(ref.open_session("a", 120000))
        with self.assertRaises(ValueError):
            ref.request("a", 119999)

    def test_get_prop_not_exempted_from_reference_budget(self):
        ref = SafeLanQuotaReference()
        ref.open_session("a", 0)
        for _ in range(60):
            self.assertTrue(ref.request("a", 0))
        self.assertFalse(ref.request("a", 0))

    def test_negative_reference_model_permanently_stalls_after_wrap(self):
        bad = HypotheticalStalledRefill()
        for _ in range(60):
            self.assertTrue(bad.request(0))
        self.assertFalse(bad.request(1000))
        self.assertFalse(bad.request(UINT32_WRAP_MS + 10))
        self.assertFalse(bad.request(UINT32_WRAP_MS + 1_000_000))
        # Contrasting a bounded 64-bit rearm reference, not OEM binary.
        good = SafeRefillBucket(PER_CONNECTION_QUOTA, 0)
        for _ in range(60):
            self.assertTrue(good.take())
        good.refresh(UINT32_WRAP_MS + 1_000_000)
        self.assertTrue(good.take())

    def test_multiple_sessions_recover_after_two_wraps(self):
        ref = SafeLanQuotaReference()
        for s in ("a", "b"):
            self.assertTrue(ref.open_session(s, 0))
            for _ in range(60):
                self.assertTrue(ref.request(s, 0))
        now = 2 * UINT32_WRAP_MS + 60001
        self.assertTrue(ref.request("a", now))
        self.assertTrue(ref.request("b", now))
        self.assertLessEqual(ref.global_bucket.tokens, GLOBAL_QUOTA)

    def test_no_automatic_reconnect_or_device_commands_exist(self):
        dangerous = ("connect", "send_packet", "flash", "ota", "power_cycle")
        self.assertTrue(all(not hasattr(SafeLanQuotaReference, n) for n in dangerous))


if __name__ == "__main__":
    unittest.main()
