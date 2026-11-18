"""Synthetic-only stream/load regressions for proposed Color4 transport policy."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from color4_control_traffic_oracle import (
    Event,plan_classic_slider,plan_music_frames,demonstration,
    CLASSIC_BUDGET,CLASSIC_SPACING_MS,WINDOW_MS
)


class Color4TrafficOracleTests(unittest.TestCase):
    def test_120_rgb_slider_updates_do_not_become_120_classic_commands(self):
        p=demonstration()["classic"]
        self.assertLessEqual(p["attempted_classic_commands"],3)
        self.assertTrue(p["last_value_forwarded_by_kind"]["rgb"])
        self.assertLess(p["attempted_classic_commands"],p["input_events"])
        self.assertFalse(p["physical_lamp_ack_verified"])

    def test_rate_and_spacing_hold_during_one_minute(self):
        events=[Event(i*10,"bright",1+i%100) for i in range(5000)]
        p=plan_classic_slider(events)
        self.assertLessEqual(p["attempted_classic_commands"],CLASSIC_BUDGET)
        timestamps=p["attempt_timestamps_ms"]
        self.assertTrue(all(y-x>=CLASSIC_SPACING_MS
                            for x,y in zip(timestamps,timestamps[1:])))

    def test_large_stream_does_not_create_an_unbounded_command_queue(self):
        events=[Event(i*2,"rgb",i%0x1000000) for i in range(19_000)]
        p=plan_classic_slider(events)
        self.assertLessEqual(len(p["attempt_timestamps_ms"]),CLASSIC_BUDGET)
        self.assertGreater(p["coalesced_updates"],1000)

    def test_brightness_and_rgb_are_not_each_others_latest_wins(self):
        e=[Event(0,"rgb",0),Event(5,"bright",30),Event(10,"rgb",15),
           Event(20,"bright",45)]
        p=plan_classic_slider(e)
        self.assertTrue(p["last_value_forwarded_by_kind"]["rgb"])
        self.assertTrue(p["last_value_forwarded_by_kind"]["bright"])
        self.assertEqual(p["attempted_classic_commands"],3)

    def test_timestamps_never_jump_backwards(self):
        with self.assertRaises(ValueError):
            plan_classic_slider([Event(100,"rgb",1),Event(99,"rgb",2)])

    def test_invalid_command_kinds_rejected_not_mapped_to_rgb(self):
        with self.assertRaises(ValueError):
            plan_classic_slider([Event(0,"set_music",1)])
        with self.assertRaises(ValueError):
            plan_classic_slider([Event(0,"power",1)])

    def test_invalid_values_rejected(self):
        for e in [Event(0,"bright",0),Event(0,"bright",101),
                  Event(0,"rgb",-1),Event(0,"rgb",0x1000000)]:
            with self.assertRaises(ValueError):
                plan_classic_slider([e])

    def test_genuine_firmware_error_is_terminal(self):
        events=[Event(i*2000,"rgb",i) for i in range(10)]
        p=plan_classic_slider(events,firmware_quota_rejection_at_ms=2000)
        self.assertEqual(p["attempted_classic_commands"],2)
        self.assertEqual(p["state"],"FIRMWARE_QUARANTINED")
        self.assertEqual(p["outcomes"],["firmware_quota_rejected_no_auto_recovery"])

    def test_ambiguous_timeout_is_never_replayed(self):
        events=[Event(i*2000,"rgb",i) for i in range(10)]
        p=plan_classic_slider(events,ambiguous_timeout_at_ms=2000)
        self.assertEqual(p["attempted_classic_commands"],2)
        self.assertEqual(p["state"],"UNCERTAIN_TRANSPORT_STOP")

    def test_unconfirmed_music_mode_has_no_classic_fallback(self):
        e=[Event(i*25,"rgb",i) for i in range(120)]
        p=plan_music_frames(e,confirmed_support=False)
        self.assertEqual(p["music_frames"],0)
        self.assertEqual(p["fallback_classic_commands"],0)
        self.assertEqual(p["classic_control_handshakes"],0)

    def test_music_mode_confirmed_support_is_separate_reverse_stream(self):
        e=[Event(i*25,"rgb",i) for i in range(120)]
        p=plan_music_frames(e,confirmed_support=True)
        self.assertEqual(p["music_frames"],30)
        self.assertEqual(p["classic_control_handshakes"],2)
        self.assertEqual(p["fallback_classic_commands"],0)
        self.assertFalse(p["music_frame_rate_is_hardware_qualified"])

    def test_no_events_generates_no_firmware_commands(self):
        self.assertEqual(plan_classic_slider([])["attempted_classic_commands"],0)
        self.assertEqual(plan_music_frames([],confirmed_support=True)["classic_control_handshakes"],0)

    def test_at_most_one_rolling_window_command_budget(self):
        events=[Event(i*1500,"rgb",i) for i in range(100)]
        p=plan_classic_slider(events)
        timestamps=p["attempt_timestamps_ms"]
        for i,x in enumerate(timestamps):
            self.assertLessEqual(sum(x <= y < x+WINDOW_MS for y in timestamps),CLASSIC_BUDGET)

    def test_quota_window_and_spacing_assumptions_are_conservative(self):
        self.assertEqual(CLASSIC_BUDGET,24)
        self.assertEqual(CLASSIC_SPACING_MS,1500)
        self.assertEqual(WINDOW_MS,60000)

    def test_invalid_simulated_event_load_rejected(self):
        with self.assertRaises(ValueError):
            plan_classic_slider([Event(i,"rgb",i) for i in range(20001)])
        with self.assertRaises(ValueError):
            plan_music_frames([Event(0,"rgb",-1)],confirmed_support=True)


if __name__=="__main__":
    unittest.main()
