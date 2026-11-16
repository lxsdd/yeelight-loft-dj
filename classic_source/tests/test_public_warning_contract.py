"""Runtime reader boundaries and diagnostic privacy, without OEM/ADB."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'private_ci'))
import page_size_warning as warning
import public_warning_contract as contract
import private_identity_control as identity
from test_page_size_warning import FOCUS, WINDOWS, RESOURCES, DISPLAY, ui


class PublicWarningContractTests(unittest.TestCase):
    def reader(self, changes=None):
        responses = {
            ('shell', 'getconf', 'PAGE_SIZE'): '16384\n',
            ('shell', 'dumpsys', 'window', 'displays'): FOCUS.replace('com.yeelight.cherry', 'example.launcher'),
            ('shell', 'dumpsys', 'window', 'windows'): WINDOWS.replace('ty=PHONE', 'ty=BASE_APPLICATION'),
            ('shell', 'getprop', 'ro.kernel.qemu'): '1\n',
            ('shell', 'getprop', 'ro.build.version.sdk'): '37\n',
        }
        responses.update({('shell', 'cmd', 'overlay', 'lookup', 'android', 'android:string/' + k): v
                          for k, v in RESOURCES.items()})
        responses.update(changes or {})
        self.calls = []
        def run(args):
            self.calls.append(tuple(args))
            return responses[tuple(args)]
        return run

    def test_real_response_contract_uses_read_only_calls_and_never_claims_ui(self):
        result = contract.check_contract(self.reader())
        self.assertEqual(result['status'], 'PUBLIC_WARNING_RUNTIME_CONTRACT_PASS')
        self.assertEqual(result['window_parser']['focused_app'], 'OTHER_OR_UNRESOLVED')
        self.assertEqual(result['synthetic_resource_rendering'], 'PASS')
        self.assertFalse(result['real_warning_ui_tested'])
        self.assertFalse(result['apk_installed'])
        self.assertFalse(result['ui_action_taken'])
        self.assertEqual(len(self.calls), 7)
        self.assertTrue(all(c[1] in ('getconf', 'dumpsys', 'cmd') for c in self.calls))
        self.assertNotIn('Synthetic app label', json.dumps(result))

    def test_wrong_kernel_or_real_window_grammar_cannot_pass(self):
        fixtures = [
            ({('shell', 'getconf', 'PAGE_SIZE'): '4096\n'}, 'PAGE_SIZE'),
            ({('shell', 'dumpsys', 'window', 'displays'): FOCUS + FOCUS}, 'LIVE_WINDOW_GRAMMAR'),
            ({('shell', 'dumpsys', 'window', 'windows'): WINDOWS.replace('mOwnerUid=', 'other=')}, 'LIVE_WINDOW_GRAMMAR'),
            ({('shell', 'dumpsys', 'window', 'windows'): WINDOWS.replace('mAttrs=', 'other=')}, 'LIVE_WINDOW_GRAMMAR'),
        ]
        for changes, stage in fixtures:
            with self.subTest(stage=stage):
                result = contract.check_contract(self.reader(changes))
                self.assertEqual(result['status'], 'PUBLIC_WARNING_CONTRACT_BLOCKED')
                self.assertEqual(result['blocked_at'], stage)

    def test_missing_or_alias_resource_fails_without_exporting_raw_text(self):
        for value in ('Error: unavailable private detail', '@1234 -> private detail'):
            result = contract.check_contract(self.reader({
                ('shell', 'cmd', 'overlay', 'lookup', 'android', 'android:string/ok'): value}))
            self.assertEqual(result['blocked_at'], 'FRAMEWORK_RESOURCES')
            self.assertNotIn('private detail', json.dumps(result))

    def test_read_failure_exposes_only_fixed_status(self):
        env = {'GITHUB_REPOSITORY': 'lxsdd/yeelight-loft-dj', 'GITHUB_ACTOR': 'lxsdd',
               'GITHUB_EVENT_NAME': 'pull_request', 'RUNNER_ENVIRONMENT': 'github-hosted'}
        output = io.StringIO()
        with patch.dict(os.environ, env, clear=True), patch.object(sys, 'argv', ['probe', '--no-oem']), \
                patch.object(subprocess, 'run', side_effect=OSError('SECRET_DETAIL')), \
                contextlib.redirect_stdout(output):
            self.assertEqual(contract.main(), 2)
        self.assertNotIn('SECRET_DETAIL', output.getvalue())
        self.assertEqual(json.loads(output.getvalue())['status'], 'PUBLIC_WARNING_CONTRACT_READ_FAILED')

    def test_untrusted_execution_context_never_starts_adb(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(sys, 'argv', ['probe', '--no-oem']), \
                patch.object(subprocess, 'run') as run, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(contract.main(), 2)
        run.assert_not_called()

    def test_private_probe_reports_exact_failed_gate_without_private_strings(self):
        evidence = {}
        result = warning.read_warning_action(ui(), FOCUS, DISPLAY,
                    lambda args: '4096\n', diagnostic=evidence)
        self.assertIsNone(result)
        self.assertEqual(evidence['decision'], 'PAGE_SIZE_NOT_16K')
        self.assertEqual(evidence['kernel_page_size'], '4K')
        evidence = {}
        self.assertIsNone(warning.warning_action(ui(message='DO_NOT_PUBLISH'), FOCUS,
                          WINDOWS, DISPLAY, '16384', RESOURCES, diagnostic=evidence))
        self.assertEqual(evidence['decision'], 'MESSAGE_OR_POSITIVE_BUTTON_NOT_VERIFIED')
        self.assertEqual(evidence['message_match'], 'NONE')
        self.assertNotIn('DO_NOT_PUBLISH', json.dumps(evidence))

    def test_private_read_exception_retains_stage_but_never_taps(self):
        def run(args):
            if args == ['shell', 'dumpsys', 'window', 'displays']: return FOCUS
            if args == ['shell', 'wm', 'size']: return DISPLAY
            raise OSError('PRIVATE_EXCEPTION_TEXT')
        with patch.object(identity, 'adb', side_effect=run) as adb, \
                patch.object(identity, 'offline_guard') as guard:
            _, result = identity.reveal_after_known_warnings(ui(), pause=lambda _: None)
        self.assertEqual(result[-1]['page_size_probe']['decision'], 'PAGE_SIZE_READ_FAILED')
        self.assertNotIn('PRIVATE_EXCEPTION_TEXT', json.dumps(result))
        guard.assert_not_called()
        self.assertFalse(any(c.args[0][:3] == ['shell', 'input', 'tap'] for c in adb.call_args_list))


if __name__ == '__main__':
    unittest.main()
