"""Multi-client quota model: purely synthetic evidence and negative tests."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from color4_multi_client_quota_oracle import (
    Color4MultiClientQuotaOracle, MODEL_GLOBAL, MODEL_PER_CONNECTION,
    MODEL_CONNECTIONS, LOCAL_BUDGET, LOCAL_SPACING_MS, WINDOW_MS, demonstrate,
)


class Color4MultiClientQuotaOracleTest(unittest.TestCase):
    def test_all_three_external_lanes_can_exhaust_shared_model_limit(self):
        d = Color4MultiClientQuotaOracle()
        for name in ("other-a", "other-b", "other-c"):
            self.assertEqual(d.open(name, "external", 0), "MODELED_OPEN")
        for name, count in (("other-a", 60), ("other-b", 60), ("other-c", 24)):
            for _ in range(count):
                self.assertEqual(d.command(name, "get_prop", 0).status,
                                 "MODELED_ACCEPTED_NOT_REAL_ACK")
        self.assertEqual(d.open("local-a", "local", 0), "MODELED_OPEN")
        denied = d.command("local-a", "set_rgb", 0)
        self.assertEqual(denied.status, "MODELED_GLOBAL_QUOTA")
        self.assertTrue(denied.host_terminal)
        self.assertEqual(denied.host_attempts, 1)
        self.assertEqual(denied.model_global_attempts, MODEL_GLOBAL)
        self.assertEqual(d.command("local-a", "get_prop", 100000).status,
                         "LOCAL_FAIL_CLOSED")

    def test_local_pacing_applies_across_two_connections(self):
        d = Color4MultiClientQuotaOracle()
        self.assertEqual(d.open("a", "local", 0), "MODELED_OPEN")
        self.assertEqual(d.open("b", "local", 0), "MODELED_OPEN")
        self.assertEqual(d.command("a", "set_rgb", 0).status,
                         "MODELED_ACCEPTED_NOT_REAL_ACK")
        self.assertEqual(d.command("b", "get_prop", 1).status, "LOCAL_PACE_DEFER")
        self.assertEqual(d.command("b", "get_prop", LOCAL_SPACING_MS).status,
                         "MODELED_ACCEPTED_NOT_REAL_ACK")
        self.assertEqual(d.snapshot(1500)["host_attempts"], 2)

    def test_per_connection_model_cap_does_not_erase_global_counter(self):
        d = Color4MultiClientQuotaOracle()
        d.open("third-party", "external", 0)
        for _ in range(MODEL_PER_CONNECTION):
            self.assertEqual(d.command("third-party", "set_rgb", 0).status,
                             "MODELED_ACCEPTED_NOT_REAL_ACK")
        self.assertEqual(d.command("third-party", "get_prop", 0).status,
                         "MODELED_CONNECTION_QUOTA")
        self.assertEqual(d.snapshot(0)["modeled_global_attempts"],
                         MODEL_PER_CONNECTION)

    def test_four_connection_cap_and_reopen_do_not_reset_global(self):
        d = Color4MultiClientQuotaOracle()
        for i in range(MODEL_CONNECTIONS):
            self.assertEqual(d.open("lane-" + str(i), "external", 0),
                             "MODELED_OPEN")
        self.assertEqual(d.open("fifth", "local", 0), "MODELED_CONNECTION_CAP")
        for _ in range(30):
            d.command("lane-0", "get_prop", 0)
        self.assertTrue(d.close("lane-0", 0))
        self.assertEqual(d.open("fifth", "external", 0), "MODELED_OPEN")
        self.assertEqual(d.snapshot(0)["modeled_global_attempts"], 30)

    def test_local_budget_survives_reconnection_and_defers_without_queue(self):
        d = Color4MultiClientQuotaOracle()
        d.open("a", "local", 0)
        for i in range(LOCAL_BUDGET):
            self.assertEqual(d.command("a", "set_rgb", i * LOCAL_SPACING_MS).status,
                             "MODELED_ACCEPTED_NOT_REAL_ACK")
        t = LOCAL_BUDGET * LOCAL_SPACING_MS
        self.assertTrue(d.close("a", t))
        self.assertEqual(d.open("b", "local", t), "MODELED_OPEN")
        self.assertEqual(d.command("b", "get_prop", t).status, "LOCAL_BUDGET_DEFER")
        self.assertEqual(d.snapshot(t)["host_attempts"], LOCAL_BUDGET)
        self.assertEqual(d.command("b", "get_prop", WINDOW_MS).status,
                         "MODELED_ACCEPTED_NOT_REAL_ACK")

    def test_explicit_quota_report_stops_local_even_below_model_threshold(self):
        d = Color4MultiClientQuotaOracle()
        d.open("a", "local", 0)
        self.assertEqual(d.command("a", "get_prop", 0, synthetic_reply="quota").status,
                         "INJECTED_QUOTA_REJECTION")
        self.assertEqual(d.snapshot(0)["modeled_global_attempts"], 0)
        self.assertEqual(d.snapshot(0)["host_attempts"], 1)
        self.assertEqual(d.command("a", "set_rgb", 60_000).status, "LOCAL_FAIL_CLOSED")

    def test_ambiguous_timeout_counts_attempt_then_prevents_replay(self):
        d = Color4MultiClientQuotaOracle()
        d.open("a", "local", 0)
        r = d.command("a", "set_rgb", 0, synthetic_reply="timeout")
        self.assertEqual(r.status, "INJECTED_AMBIGUOUS_TIMEOUT")
        self.assertEqual(r.model_global_attempts, 1)
        self.assertEqual(d.command("a", "set_rgb", 1500).status, "LOCAL_FAIL_CLOSED")
        self.assertTrue(d.close("a", 2000))
        d.open("b", "local", 2000)
        self.assertEqual(d.command("b", "set_rgb", 4000).status, "LOCAL_FAIL_CLOSED")

    def test_music_start_uses_classic_accounting_but_frames_cannot_fallback(self):
        d = Color4MultiClientQuotaOracle()
        d.open("a", "local", 0)
        self.assertEqual(d.command("a", "set_music", 0).status,
                         "MODELED_ACCEPTED_NOT_REAL_ACK")
        for method in ("music_frame", "chroma_udp", "udp_sess_new", "tcp_fallback"):
            with self.assertRaises(ValueError):
                d.command("a", method, 1500)
        self.assertEqual(d.snapshot(1500)["host_attempts"], 1)

    def test_rolling_window_and_multiple_uptime_wraps_work_in_reference(self):
        d = Color4MultiClientQuotaOracle()
        d.open("a", "external", 0)
        for _ in range(MODEL_PER_CONNECTION):
            d.command("a", "get_prop", 0)
        self.assertEqual(d.command("a", "get_prop", WINDOW_MS - 1).status,
                         "MODELED_CONNECTION_QUOTA")
        # A 64-bit-safe reference does NOT prove actual Color4 timer behavior.
        for t in (WINDOW_MS, (1 << 32) + WINDOW_MS, 2 * (1 << 32) + WINDOW_MS):
            self.assertEqual(d.command("a", "get_prop", t).status,
                             "MODELED_ACCEPTED_NOT_REAL_ACK")

    def test_clock_and_label_validation_rejects_aliases_and_bad_types(self):
        d = Color4MultiClientQuotaOracle()
        for bad in (True, -1, 1.5, "1"):
            with self.assertRaises(ValueError):
                d.snapshot(bad)
        for label in ("192.168.1.21", "", "a/b", "USER@EXAMPLE"):
            with self.assertRaises(ValueError):
                d.open(label, "external", 0)
        with self.assertRaises(ValueError):
            d.open("a", "app", 0)
        self.assertEqual(d.open("a", "local", 10), "MODELED_OPEN")
        with self.assertRaises(ValueError):
            d.command("a", "set_rgb", 9)
        with self.assertRaises(ValueError):
            d.command("a", "set_rgb", 10, synthetic_reply="mystery")

    def test_fourteen_independent_devices_do_not_share_budget_or_stop(self):
        devices = [Color4MultiClientQuotaOracle() for _ in range(14)]
        for d in devices:
            d.open("a", "local", 0)
        for i, d in enumerate(devices):
            self.assertEqual(d.command("a", "set_rgb", 0,
                                      synthetic_reply="quota" if i == 7 else "ok").status,
                             "INJECTED_QUOTA_REJECTION" if i == 7 else
                             "MODELED_ACCEPTED_NOT_REAL_ACK")
        for i, d in enumerate(devices):
            response = d.command("a", "set_rgb", 1500).status
            self.assertEqual(response, "LOCAL_FAIL_CLOSED" if i == 7
                             else "MODELED_ACCEPTED_NOT_REAL_ACK")
        self.assertEqual(sum(x.snapshot(1500)["host_terminal"] for x in devices), 1)

    def test_large_burst_never_becomes_large_classic_command_queue(self):
        d = Color4MultiClientQuotaOracle()
        d.open("a", "local", 0)
        counts = {}
        for _ in range(5000):
            status = d.command("a", "set_rgb", 0).status
            counts[status] = counts.get(status, 0) + 1
        self.assertEqual(counts, {
            "MODELED_ACCEPTED_NOT_REAL_ACK": 1,
            "LOCAL_PACE_DEFER": 4999,
        })
        self.assertEqual(d.snapshot(0)["host_attempts"], 1)

    def test_demo_preserves_unverified_outcome_and_no_identifiers(self):
        result = demonstrate()
        self.assertEqual(result["modeled_result"], "MODELED_GLOBAL_QUOTA")
        self.assertFalse(result["root_cause_proven"])
        self.assertFalse(result["hardware_qualified"])
        self.assertNotIn("192.", repr(result))
        self.assertNotIn("token", repr(result))


    def test_three_lan_outages_with_working_cloud_and_no_force_stop_recovery(self):
        lamps = [Color4MultiClientQuotaOracle() for _ in range(5)]
        for lamp in lamps:
            self.assertEqual(lamp.open("loft", "local", 0), "MODELED_OPEN")
            self.assertEqual(lamp.open("classic", "external", 0), "MODELED_OPEN")
        for lamp in lamps[:3]:
            lamp.inject_lan_service_stall(100)
        for i, lamp in enumerate(lamps):
            self.assertEqual(lamp.modeled_cloud_path(101),
                             "MODELED_CLOUD_ROUTE_INDEPENDENT_OF_LAN")
            expected = ("INJECTED_LAN_SERVICE_UNAVAILABLE" if i < 3
                        else "MODELED_ACCEPTED_NOT_REAL_ACK")
            self.assertEqual(lamp.command("classic", "set_power", 102).status, expected)
            self.assertEqual(lamp.simulate_app_force_stop(103), 1)
            self.assertEqual(lamp.command("classic", "get_prop", 104).status, expected)
        # Reboot ONE affected lamp at a time, leave the other controls as-is.
        for i, lamp in enumerate(lamps[:3]):
            lamp.simulate_power_cycle(105 + i)
            self.assertFalse(lamp.snapshot(110)["injected_lan_service_stalled"])
            self.assertEqual(lamp.open("classic", "external", 110), "MODELED_OPEN")
            self.assertEqual(lamp.command("classic", "get_prop", 110).status,
                             "MODELED_ACCEPTED_NOT_REAL_ACK")
        self.assertEqual(sum(lamp.snapshot(111)["injected_lan_service_stalled"]
                             for lamp in lamps), 0)

    def test_reboot_cannot_erase_host_quarantine_or_fake_a_verified_recovery(self):
        lamp = Color4MultiClientQuotaOracle()
        lamp.open("loft", "local", 0)
        lamp.open("classic", "external", 0)
        self.assertEqual(lamp.command("loft", "get_prop", 0,
                         synthetic_reply="quota").status, "INJECTED_QUOTA_REJECTION")
        lamp.simulate_power_cycle(100)
        self.assertEqual(lamp.open("loft-next", "local", 200), "MODELED_OPEN")
        self.assertEqual(lamp.command("loft-next", "set_rgb", 200).status,
                         "LOCAL_FAIL_CLOSED")
        self.assertTrue(lamp.snapshot(200)["host_terminal"])
        self.assertEqual(lamp.open("classic", "external", 200), "MODELED_OPEN")
        self.assertEqual(lamp.command("classic", "set_power", 200).status,
                         "MODELED_ACCEPTED_NOT_REAL_ACK")

    def test_ambiguous_lan_stall_never_masquerades_as_quota_receipt(self):
        lamp = Color4MultiClientQuotaOracle()
        lamp.open("classic", "external", 0)
        lamp.inject_lan_service_stall(0)
        denied = lamp.command("classic", "get_prop", 0)
        self.assertEqual(denied.status, "INJECTED_LAN_SERVICE_UNAVAILABLE")
        self.assertEqual(denied.model_global_attempts, 0)
        self.assertFalse(denied.host_terminal)
        self.assertTrue(lamp.snapshot(0)["injected_lan_service_stalled"])
        self.assertEqual(lamp.modeled_cloud_path(0),
                         "MODELED_CLOUD_ROUTE_INDEPENDENT_OF_LAN")

    def test_per_device_stall_isolation_and_strict_virtual_time_validation(self):
        lamps = [Color4MultiClientQuotaOracle() for _ in range(14)]
        for i, lamp in enumerate(lamps):
            if i in (2, 4, 9):
                lamp.inject_lan_service_stall(1)
        lamps[4].simulate_power_cycle(2)
        self.assertTrue(lamps[2].snapshot(2)["injected_lan_service_stalled"])
        self.assertFalse(lamps[4].snapshot(2)["injected_lan_service_stalled"])
        self.assertTrue(lamps[9].snapshot(2)["injected_lan_service_stalled"])
        for method in (lamps[0].inject_lan_service_stall,
                       lamps[0].simulate_app_force_stop,
                       lamps[0].simulate_power_cycle,
                       lamps[0].modeled_cloud_path):
            for bad in (-1, True, "bad", 1.5):
                with self.assertRaises(ValueError):
                    method(bad)


if __name__ == "__main__":
    unittest.main()
