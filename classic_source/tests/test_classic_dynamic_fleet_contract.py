"""Reject reintroduction of artificial group-size ceilings in Classic music."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DynamicFleetContract(unittest.TestCase):
    def test_selection_and_transport_not_hard_capped(self):
        runtime = (ROOT / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text()
        transport = (ROOT / "checkpoint/src/ClassicYeelightMusicTransport.kt").read_text()
        self.assertNotRegex(runtime, r"selectedLampIps\.size\s*(?:<=|<|==)\s*\d+")
        self.assertNotRegex(transport, r"peers\.size\s*(?:<=|<|==)\s*\d+")
        self.assertIn("selectedLampIps.distinct().size == selectedLampIps.size", runtime)
        self.assertIn("peers.all(::validLanIp)", transport)
        self.assertIn("if (!ips.isEmpty())", (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjService.java").read_text())

    def test_spatial_geometry_uses_dynamic_count(self):
        spatial = (ROOT / "checkpoint/src/ClassicSpatialChoreography.kt").read_text()
        self.assertIn("require(count > 0 && index in 0 until count)", spatial)
        self.assertNotRegex(spatial, r"count\s+in\s+1\.\.\d+")
        self.assertIn("count.toLong()", spatial)
        integration = (ROOT / "checkpoint/tests/ClassicSpatialChoreographyTest.kt").read_text()
        self.assertIn("listOf(1, 5, 14, 15, 32, 64, 128)", integration)
        self.assertIn("tick.sent == fleetIds.toSet()", integration)
        runtime = (ROOT / "checkpoint/tests/CheckpointRuntimeTest.kt").read_text()
        self.assertIn("listOf(5, 15, 33, 72)", runtime)
        self.assertIn("stoppedIds == ids.toSet()", runtime)


if __name__ == "__main__":
    unittest.main()
