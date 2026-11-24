"""No toolchain download/installation and no device action: fail-closed cross-platform guard."""
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import contextlib
import io
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
from bridge.build_bridge import (
    build, sdk_file, ToolError, stage_gradle_inputs, _gradle_stage_reason
)

class GradleBridgeStageTests(unittest.TestCase):
    """Mock only: no Gradle, network, signing, APK or hardware operations."""

    def stage(self, responses):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out = root/'private-ephemeral'
            out.mkdir()
            invocations = []
            def fake_run(args, **kwargs):
                invocations.append((tuple(args), kwargs))
                result, output = responses[len(invocations)-1]
                kwargs['stdout'].write(output)
                return SimpleNamespace(returncode=result)
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch('bridge.build_bridge.subprocess.run', side_effect=fake_run):
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    try:
                        stage_gradle_inputs('/pinned/gradle', root/'public-project',
                                            root/'sdk', out)
                        failure = None
                    except ToolError as error:
                        failure = str(error)
            self.assertFalse((out/'gradle-bridge-private.log').exists())
            self.assertEqual(stdout.getvalue(), '')
            self.assertEqual(stderr.getvalue(), '')
            for arguments, options in invocations:
                self.assertEqual(arguments[-1], 'stageBridgeInputs')
                self.assertEqual(options['env']['ANDROID_HOME'], str(root/'sdk'))
                self.assertIs(options['stderr'], subprocess.STDOUT)
            return failure, len(invocations)

    def test_success_is_single_call_and_raw_output_never_emitted(self):
        self.assertEqual(self.stage([(0, b'private host compiler path')]),
                         (None, 1))

    def test_one_transient_network_retry_but_never_more(self):
        self.assertEqual(self.stage([
            (1, b'java.net.SocketTimeoutException: read timed out private'),
            (0, b'BUILD SUCCESSFUL')]), (None, 2))
        self.assertEqual(self.stage([
            (1, b'Connection reset during dependency download secret=1'),
            (1, b'connection reset secret=2')]),
            ('GRADLE_BRIDGE_TRANSIENT_NETWORK', 2))

    def test_compiler_dependency_daemon_unknown_fail_without_retry(self):
        cases = (
            (b"Execution failed for task ':compileKotlin'. Compilation error: PrivateType",
             'GRADLE_BRIDGE_COMPILER_FAILED'),
            (b'Could not find org.example:kotlin-secret:0.0.0',
             'GRADLE_BRIDGE_DEPENDENCY_UNAVAILABLE'),
            (b'Gradle build daemon disappeared unexpectedly',
             'GRADLE_BRIDGE_DAEMON_FAILED'),
            (b'Private compiler failed for an undocumented reason',
             'GRADLE_BRIDGE_PROCESS_FAILED_UNCLASSIFIED'),
        )
        for output, code in cases:
            with self.subTest(reason=code):
                self.assertEqual(self.stage([(1, output)]), (code, 1))

    def test_private_content_is_not_a_recognized_retry_reason(self):
        self.assertEqual(_gradle_stage_reason(b'private app credentials'),
                         'GRADLE_BRIDGE_PROCESS_FAILED_UNCLASSIFIED')


class BuildBridgeGuards(unittest.TestCase):
    def test_missing_sdk_fails_without_output_or_network(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)
            with self.assertRaisesRegex(ToolError,'ANDROID_SDK_MISSING'):
                build(path,path/'bridge-build')
            self.assertFalse((path/'bridge-build').exists())

    def test_refuses_existing_output_before_tool_invocation(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)
            (path/'bridge-build').mkdir()
            with self.assertRaisesRegex(ToolError,'OUTPUT_ALREADY_EXISTS'):
                build(path,path/'bridge-build')
