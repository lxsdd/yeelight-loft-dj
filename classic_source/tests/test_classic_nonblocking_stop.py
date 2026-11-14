"""Fail-closed source boundary for responsive Classic-owned Music cleanup.

These are source-level regression tests, not a claim of measured Android
foreground-service timing or physical Color4 state.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjService.java"
COORDINATOR = ROOT / "checkpoint/src/ClassicMusicCoordinator.kt"
TRANSPORT = ROOT / "checkpoint/src/ClassicYeelightMusicTransport.kt"


class NonblockingStopContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.service = SERVICE.read_text("utf-8")
        cls.coordinator = COORDINATOR.read_text("utf-8")
        cls.transport = TRANSPORT.read_text("utf-8")

    def test_explicit_user_stop_fences_immediately_and_stops_asynchronously(self):
        service = self.service
        beginning = service.index("private void stopSession(")
        end = service.index("@Override public void onDestroy()", beginning)
        body = service[beginning:end]
        self.assertLess(body.index("sessionFence.cancel()"),
                        body.index("Thread cleanup = new Thread"))
        self.assertLess(body.index("currentToken = 0L"),
                        body.index("Thread cleanup = new Thread"))
        self.assertIn('currentState = instance == null ? finalState : "DJ wird beendet', body)
        self.assertIn('new Thread(() ->', body)
        self.assertIn('closeOwnedRuntime(instance)', body)
        self.assertIn("cleanupHandler.post(() -> finishSessionStop(finalState, clean, true))", body)
        self.assertIn('cleanup.start()', body)
        self.assertNotIn("stopSelf();\n        Thread cleanup", body)

    def test_android_destroy_path_is_synchronous_and_cannot_orphan_socket(self):
        body = self.service[self.service.index("private void stopSession("):
                            self.service.index("@Override public void onDestroy()")]
        self.assertIn('if (!requestStopSelf)', body)
        self.assertIn('finishSessionStop(finalState, closeOwnedRuntime(instance), false)', body)
        self.assertIn('catch (RuntimeException unableToStartThread)', body)
        self.assertIn('finishSessionStop(finalState, closeOwnedRuntime(instance), true)', body)
        self.assertIn('catch (Exception | LinkageError error)', body)

    def test_foreground_ends_only_after_owned_cleanup_finishes(self):
        service = self.service
        beginning = service.index("private void finishSessionStop(")
        ending = service.index("@Override public void onDestroy()", beginning)
        method = service[beginning:ending]
        self.assertLess(method.index("stopForeground(true)"), method.index("stopSelf()"))
        self.assertIn('clean ? finalState : "Stopp nicht bestätigt', method)
        self.assertNotIn("set_power", method)
        self.assertNotIn("get_prop", method)
        self.assertNotIn("restore", method)

    def test_music_ownership_confirmation_not_weakened(self):
        coordinator = self.coordinator[ self.coordinator.index(
            '@Synchronized fun stop(id: String): State'):self.coordinator.index(
            '@Synchronized fun confirmRecovery(')]
        self.assertIn('d.state != State.OWNED || !d.ownsSocket', coordinator)
        self.assertIn('transport.requestStop(id)', coordinator)
        self.assertIn('transport.readMusic(id)', coordinator)
        self.assertIn('if (active != false) return quarantine(', coordinator)
        self.assertIn('closeTracked(d)', coordinator)
        self.assertIn('override fun requestStop(id: String)', self.transport)
        self.assertIn('set_music', self.transport)
        self.assertNotIn('set_power', coordinator)
        self.assertNotIn('restoreState', coordinator)


if __name__ == "__main__":
    unittest.main()
