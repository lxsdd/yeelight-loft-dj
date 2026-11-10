"""Source-bound Classic startup cancellation contract: no real OEM or devices.

The Kotlin behavior is also covered by the real CancelDuringStartTest JVM main.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class CancellationWiringTest(unittest.TestCase):
    def test_runtime_checks_stop_before_next_lamp(self):
        runtime = (ROOT / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text()
        engine = (ROOT / "checkpoint/src/ClassicDjEngine.kt").read_text()
        coordinator = (ROOT / "checkpoint/src/ClassicMusicCoordinator.kt").read_text()
        self.assertIn("newEngine.select(selectedLampIps) {", runtime)
        self.assertIn("state == State.STARTING", runtime)
        self.assertIn("coordinator.startGroup(ids, allowNextStart)", engine)
        self.assertIn("if (!allowNextStart()) break", coordinator)
        self.assertIn("ids.forEach { device(it) }", coordinator)
        self.assertIn("return coordinator.stopGroup(ids)", engine)


if __name__ == "__main__":
    unittest.main()
