"""Synthetic WM/framework fixtures and real probe orchestration; no OEM/ADB."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'private_ci'))
import page_size_warning as warning
import private_identity_control as ident

MESSAGE = 'Synthetic 16 KB ELF alignment warning. Run using page size compatible mode.'
RESOURCES = {
    'page_size_compat_apk_warning': 'Synthetic 16 KB APK alignment warning. Compatible mode applies.',
    'page_size_compat_elf_warning': MESSAGE,
    'page_size_compat_apk_and_elf_warning': 'Synthetic 16 KB APK and ELF alignment warning. Compatible mode applies.',
    'ok': 'OK',
}
DISPLAY = 'Physical size: 1080x2400'
FOCUS = ('  mCurrentFocus=Window{a1 u0 Synthetic app label}\n'
         '  mFocusedApp=ActivityRecord{c1 u0 com.yeelight.cherry/.MainActivity t7}\n')
WINDOWS = ('  Window #0 Window{a1 u0 Synthetic app label}:\n'
           '    mOwnerUid=1000 showForAllUsers=false package=android appop=NONE\n'
           '    mAttrs={(0,0)(wrapxwrap) gr=CENTER ty=PHONE fmt=TRANSLUCENT}\n'
           '  Window #1 Window{a2 u0 another app}:\n'
           '    mOwnerUid=10055 showForAllUsers=false package=example appop=NONE\n'
           '    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION}\n')


def ui(package='android', message=MESSAGE):
    root = ET.Element('hierarchy')
    container = ET.SubElement(root, 'node', {'package': package})
    ET.SubElement(container, 'node', {'package': package, 'resource-id': 'android:id/message',
                                    'text': message})
    ET.SubElement(container, 'node', {'package': package, 'resource-id': 'android:id/button1',
                                    'text': 'OK', 'class': 'android.widget.Button',
                                    'clickable': 'true', 'enabled': 'true',
                                    'bounds': '[50,100][150,140]'})
    return '<?xml version="1.0"?>' + ET.tostring(root, encoding='unicode')


OEM_UI = ('<?xml version="1.0"?><hierarchy>'
          '<node package="com.yeelight.cherry" resource-id="com.yeelight.cherry:id/original_content"/>'
          '<node package="com.yeelight.cherry" text="Loft DJ" clickable="true" enabled="true" '
          'bounds="[10,10][50,50]"/></hierarchy>')
DJ_UI = ('<?xml version="1.0"?><hierarchy>'
         '<node package="com.yeelight.cherry" text="Loft DJ · Yeelight Classic"/>'
         '<node package="com.yeelight.cherry" text="Mikrofon starten" clickable="true" enabled="true"/>'
         '<node package="com.yeelight.cherry" text="DJ stoppen" clickable="true" enabled="true"/>'
         '</hierarchy>')
CLEAN = {'target_crash': False, 'unattributed_fatal': False, 'exception_category': 'NONE'}


class PageSizeWarningTests(unittest.TestCase):
    def action(self, **changes):
        data = dict(xml=ui(), focus=FOCUS, windows=WINDOWS, display=DISPLAY,
                    page_size='16384', resources=RESOURCES)
        data.update(changes)
        return warning.warning_action(**data)

    def test_overlay_lookup_resolves_only_aosp_reference_arrows(self):
        long_text = RESOURCES['page_size_compat_apk_warning']
        self.assertEqual(
            warning.rendered_resource('@0x01040001 -> ' + long_text),
            long_text)
        self.assertEqual(
            warning.rendered_resource('@android:string/page_size_compat_apk_warning -> ' + long_text),
            long_text)
        for unverified in ('@0x01040001', '@android:string/page_size_compat_apk_warning',
                           '0x01040001', 'private -> ' + long_text,
                           '@0x01040001 -> @0x01040002',
                           '@0x01040001 -> ' + long_text + '\nSECRET',
                           'Error: failed to get the resource'):
            with self.subTest(value=unverified[:35]):
                self.assertIsNone(warning.rendered_resource(unverified))

    def test_only_resolved_framework_text_can_authorize_private_known_dialog(self):
        message = RESOURCES['page_size_compat_apk_warning']
        unresolved = dict(RESOURCES,
                          page_size_compat_apk_warning='@0x01040001')
        self.assertIsNone(self.action(xml=ui(message=message), resources=unresolved))
        resolved = dict(RESOURCES,
                        page_size_compat_apk_warning='@0x01040001 -> ' + message)
        self.assertEqual(self.action(xml=ui(message=message), resources=resolved),
                         ((100, 120), 'APK_ALIGNMENT'))
        # Arbitrary arrows cannot manufacture a trusted framework resolution.
        forged = dict(RESOURCES,
                      page_size_compat_apk_warning='private -> ' + message)
        self.assertIsNone(self.action(xml=ui(message=message), resources=forged))

    def test_title_is_not_a_technical_dialog_name(self):
        self.assertEqual(self.action(), ((100, 120), 'ELF_ALIGNMENT'))
        self.assertEqual(self.action(windows=WINDOWS.replace('ty=PHONE', 'ty=2002')),
                         ((100, 120), 'ELF_ALIGNMENT'))
        for resource, category in warning.RESOURCES.items():
            self.assertEqual(self.action(xml=ui(message=RESOURCES[resource]))[1], category)

    def test_real_aosp_nested_soft_input_attributes_keep_phone_type(self):
        for attributes in ('sim={adjust=pan} ty=PHONE',
                           'sim={state=hidden adjust=resize} ty=2002',
                           'sim={adjust=nothing} ty=PHONE token=Binder{ab12}'):
            with self.subTest(attributes=attributes):
                self.assertEqual(self.action(windows=WINDOWS.replace('ty=PHONE', attributes)),
                                 ((100, 120), 'ELF_ALIGNMENT'))

    def test_unbalanced_duplicate_and_cross_window_attributes_fail_closed(self):
        for text in (
                WINDOWS.replace('ty=PHONE', 'sim={adjust=pan ty=PHONE'),
                WINDOWS.replace('ty=PHONE', 'sim={adjust=pan} ty=BASE_APPLICATION'),
                WINDOWS.replace('ty=PHONE', 'sim={ty=PHONE} ty=BASE_APPLICATION'),
                WINDOWS.replace('    mAttrs=', '    mAttrs={ty=PHONE}\n    mAttrs=', 1),
                WINDOWS.replace('ty=PHONE', 'x' * 8200 + ' ty=PHONE')):
            with self.subTest(text=text[:60]):
                self.assertIsNone(self.action(windows=text))

    def test_exact_running_framework_html_and_localized_text(self):
        text = 'Synthetische 16 KB Warnung. Informationen unter '
        resources = dict(RESOURCES, page_size_compat_elf_warning=(
            text + '<a href="https://example.invalid/">Hilfeseite</a>'))
        self.assertIsNotNone(self.action(xml=ui(message=text + 'Hilfeseite'), resources=resources))
        self.assertIsNone(self.action(xml=ui(message=text + 'Hilfeseite verändert'), resources=resources))
        self.assertIsNone(self.action(resources=dict(RESOURCES, page_size_compat_elf_warning='Error: missing')))
        self.assertIsNone(self.action(resources=dict(RESOURCES, page_size_compat_apk_warning=MESSAGE)))

    def test_uid_type_and_window_token_cannot_be_borrowed(self):
        for windows in (WINDOWS.replace('mOwnerUid=1000 ', 'mOwnerUid=10001 '),
                        WINDOWS.replace('package=android', 'package=com.yeelight.cherry'),
                        WINDOWS.replace('ty=PHONE', 'ty=APPLICATION_OVERLAY'),
                        WINDOWS.replace('Window{a1', 'Window{b1'),
                        WINDOWS + WINDOWS):
            with self.subTest(windows=windows[:70]):
                self.assertIsNone(self.action(windows=windows))
        # The correct system UID/type elsewhere cannot validate an app window.
        moved = WINDOWS.replace('a1 u0 Synthetic app label', 'a2 u0 another app')
        self.assertIsNone(self.action(windows=moved))

    def test_focus_must_be_unique_and_select_this_classic_app(self):
        for focus in ('mCurrentFocus=null\n', FOCUS + FOCUS,
                      FOCUS.replace('com.yeelight.cherry/', 'prefix.com.yeelight.cherry/'),
                      FOCUS.replace('com.yeelight.cherry/', 'com.other.app/'),
                      FOCUS.split('mFocusedApp=')[0]):
            with self.subTest(focus=focus):
                self.assertIsNone(self.action(focus=focus))

    def test_non_phone_system_window_is_read_only_classified_never_tapped(self):
        for kind, expected in (
            ('BASE_APPLICATION', 'APP_BASE'),
            ('APPLICATION', 'APP_MAIN'),
            ('SYSTEM_DIALOG', 'SYSTEM_DIALOG'),
            ('APPLICATION_ATTACHED_DIALOG', 'APP_ATTACHED_DIALOG'),
            ('com.private.unknown', 'OTHER_UNCLASSIFIED'),
        ):
            with self.subTest(kind=kind):
                evidence = {}
                focused = WINDOWS.replace('ty=PHONE', 'ty=' + kind)
                self.assertIsNone(self.action(windows=focused, diagnostic=evidence))
                self.assertEqual(evidence['window_type'], 'OTHER')
                self.assertEqual(evidence['window_type_detail'], expected)
                self.assertEqual(evidence['focused_window_category'],
                                 'OTHER_OR_UNVERIFIED')
                self.assertNotIn(kind, str(evidence) if kind.startswith('com.') else '')
        for token, label in (
            (' DeprecatedTargetSdkVersionDialog}', 'AOSP_DEPRECATED_TARGET_DIALOG'),
            (' Application Not Responding: app}', 'ANDROID_ANR_DIALOG'),
            (' com.android.permissioncontroller/.Permissions}', 'ANDROID_PERMISSION_CONTROLLER'),
        ):
            with self.subTest(token=label):
                evidence = {}
                focus = FOCUS.replace(' Synthetic app label}', token)
                windows = WINDOWS.replace(' Synthetic app label}', token)
                self.assertIsNone(self.action(
                    focus=focus, windows=windows.replace('ty=PHONE', 'ty=SYSTEM_DIALOG'),
                    diagnostic=evidence))
                self.assertEqual(evidence['focused_window_category'], label)
                self.assertEqual(evidence['window_type_detail'], 'SYSTEM_DIALOG')

    def test_android_2038_overlay_is_classified_but_never_tapped(self):
        clone = warning.CLONE
        focus = FOCUS.replace('com.yeelight.cherry/', clone + '/')
        windows = WINDOWS.replace('ty=PHONE', 'ty=2038')
        for message, category in (
            (MESSAGE, 'FRAMEWORK_PAGE_SIZE_MESSAGE_MATCH_NO_ACTION'),
            ('Unknown private system overlay: not the page-size warning',
             'NOT_AOSP_PAGE_SIZE_MESSAGE'),
        ):
            with self.subTest(category=category):
                details = warning.inspect_app_overlay_readonly(
                    ui(message=message), focus, windows, RESOURCES,
                    target_package=clone)
                self.assertEqual(details['status'], category)
                self.assertFalse(details['interaction_performed'])
                self.assertFalse(details['raw_ui_logged'])
                self.assertTrue(details['system_message_node'])
                self.assertTrue(details['system_positive_button_node'])
                self.assertNotIn(message, str(details))
        self.assertEqual(warning.inspect_app_overlay_readonly(
            ui(), focus, windows, {}, target_package=clone)['status'],
            'FRAMEWORK_PAGE_SIZE_RESOURCES_UNAVAILABLE')
        self.assertEqual(warning.inspect_app_overlay_readonly(
            ui(), focus, windows.replace('package=android','package=private'),
            RESOURCES, target_package=clone)['status'],
            'OVERLAY_WINDOW_NOT_VERIFIED')
        self.assertEqual(warning.inspect_app_overlay_readonly(
            ui(), focus, WINDOWS, RESOURCES, target_package=clone)['status'],
            'OVERLAY_WINDOW_NOT_VERIFIED')
        self.assertEqual(warning.inspect_app_overlay_readonly(
            ui(package='com.other.app'), focus, windows,
            RESOURCES, target_package=clone)['status'],
            'OVERLAY_UI_PACKAGE_UNVERIFIED')
        self.assertEqual(warning.inspect_app_overlay_readonly(
            '<!DOCTYPE private><hierarchy/>', focus, windows,
            RESOURCES, target_package=clone)['status'], 'OVERLAY_UI_INVALID')

    def test_no_permission_or_unknown_message_or_ambiguous_button(self):
        for xml in (ui(package='com.yeelight.cherry'), ui(message='Allow this app to record audio?'),
                    ui().replace('button1', 'button2'), ui().replace('text="OK"', 'text="Allow"'),
                    ui().replace('enabled="true"', 'enabled="false"'),
                    ui().replace('android.widget.Button', 'android.widget.TextView'),
                    ui().replace('[50,100][150,140]', '[0,0][9999,9999]'),
                    ui().replace('</hierarchy>', '<node package="android" resource-id="android:id/button2"/></hierarchy>'),
                    ui().replace('</hierarchy>', '<node package="android" clickable="true"/></hierarchy>')):
            with self.subTest(xml=xml[:60]):
                self.assertIsNone(self.action(xml=xml))

    def test_clone_package_must_be_explicit_and_focused_for_page_size_action(self):
        clone = warning.CLONE
        focus = FOCUS.replace('com.yeelight.cherry/', clone + '/')
        self.assertIsNone(self.action(focus=focus))
        self.assertEqual(self.action(focus=focus, target_package=clone),
                         ((100, 120), 'ELF_ALIGNMENT'))
        self.assertIsNone(self.action(target_package=clone))
        for bad in (clone + '.evil', 'com.other.app', '', None, 7):
            with self.subTest(bad=bad):
                self.assertIsNone(self.action(
                    focus=focus, target_package=bad))
        self.assertIsNone(self.action(focus=focus.replace(
            clone + '/', 'prefix.' + clone + '/'), target_package=clone))

    def test_clone_read_action_uses_same_exact_focus_and_resources(self):
        clone = warning.CLONE
        focus = FOCUS.replace('com.yeelight.cherry/', clone + '/')
        calls = []
        def run(args):
            calls.append(tuple(args))
            if args == ['shell', 'getconf', 'PAGE_SIZE']: return '16384'
            if args == ['shell', 'dumpsys', 'window', 'windows']: return WINDOWS
            if args[:4] == ['shell', 'cmd', 'overlay', 'lookup']:
                return RESOURCES[args[-1].split('/')[-1]]
            raise AssertionError(args)
        self.assertEqual(warning.read_warning_action(
            ui(), focus, DISPLAY, run, target_package=clone),
            ((100, 120), 'ELF_ALIGNMENT'))
        self.assertIsNone(warning.read_warning_action(
            ui(), FOCUS, DISPLAY, run, target_package=clone))
        self.assertFalse(any('input' in c or 'grant' in c for c in calls))

    def test_malformed_oversized_and_non_16k_inputs_fail_closed(self):
        for xml in (None, '', '<malformed>', '<!DOCTYPE x>' + ui(), ui() * 20000):
            self.assertIsNone(self.action(xml=xml))
        self.assertIsNone(self.action(page_size='4096'))
        self.assertIsNone(self.action(display='Physical size: 0x0'))
        self.assertIsNone(self.action(resources={}))

    def test_resource_queries_are_read_only_and_fixed(self):
        calls = []
        def run(args):
            calls.append(args)
            if args[1] == 'getconf': return '16384\n'
            if args[1] == 'dumpsys': return WINDOWS
            return RESOURCES[args[-1].split('/')[-1]]
        self.assertEqual(warning.read_warning_action(ui(), FOCUS, DISPLAY, run),
                         ((100, 120), 'ELF_ALIGNMENT'))
        self.assertEqual(len(calls), 6)
        self.assertTrue(all(x[:4] == ['shell', 'cmd', 'overlay', 'lookup'] for x in calls[2:]))
        self.assertFalse(any('setprop' in x or 'input' in x or 'grant' in x for x in calls))


class WarningNavigationTests(unittest.TestCase):
    def read(self, args):
        if args == ['shell', 'wm', 'size']: return DISPLAY
        if args == ['shell', 'getconf', 'PAGE_SIZE']: return '16384\n'
        if args == ['shell', 'dumpsys', 'window', 'windows']: return WINDOWS
        if args == ['shell', 'dumpsys', 'window', 'displays']: return FOCUS
        if args[:4] == ['shell', 'cmd', 'overlay', 'lookup']:
            return RESOURCES[args[-1].split('/')[-1]]
        if args[:3] == ['shell', 'input', 'tap']:
            self.taps.append(args)
            return ''
        raise AssertionError(args)

    def test_real_warning_path_taps_once_then_requires_recaptured_oem_ui(self):
        self.taps = []
        with patch.object(ident, 'adb', side_effect=self.read), \
                patch.object(ident, 'offline_guard') as guard, \
                patch.object(ident, 'capture_private_hierarchy', return_value=(OEM_UI, 'CAPTURED')):
            xml, evidence = ident.reveal_after_known_warnings(ui(), pause=lambda _: None)
        self.assertEqual(xml, OEM_UI)
        self.assertEqual(self.taps, [['shell', 'input', 'tap', '100', '120']])
        guard.assert_called_once()
        self.assertEqual(evidence[0]['status'], 'EXACT_AOSP_PAGE_SIZE_WARNING_ACKNOWLEDGED')
        self.assertFalse(evidence[0]['product_ui_accepted'])
        self.assertNotIn('Synthetic app label', str(evidence))
        self.assertNotIn(MESSAGE, str(evidence))

    def test_repeated_warning_is_not_tapped_again(self):
        self.taps = []
        with patch.object(ident, 'adb', side_effect=self.read), \
                patch.object(ident, 'offline_guard'), \
                patch.object(ident, 'capture_private_hierarchy', return_value=(ui(), 'CAPTURED')):
            _, evidence = ident.reveal_after_known_warnings(ui(), pause=lambda _: None)
        self.assertEqual(len(self.taps), 1)
        self.assertEqual(evidence[-1]['status'], 'REPEATED_WARNING_NO_ACTION')

    def test_unknown_dialog_and_focus_race_never_tap(self):
        for race in (False, True):
            self.taps = []
            reads = 0
            def run(args):
                nonlocal reads
                if args == ['shell', 'dumpsys', 'window', 'displays']:
                    reads += 1
                    if race and reads == 2:
                        return FOCUS.replace('Window{a1', 'Window{ff')
                return self.read(args)
            with patch.object(ident, 'adb', side_effect=run), \
                    patch.object(ident, 'offline_guard') as guard:
                _, evidence = ident.reveal_after_known_warnings(
                    ui() if race else ui(message='Unknown dialog'), pause=lambda _: None)
            self.assertEqual(self.taps, [])
            guard.assert_not_called()
            self.assertFalse(evidence[-1]['acknowledged'])

    def test_oem_visibility_requires_original_views_not_only_injected_button(self):
        snapshot = 'topResumedActivity=ActivityRecord{c1 u0 com.yeelight.cherry/.MainActivity t7}'
        self.assertTrue(ident.evaluate_original_surface(snapshot, CLEAN, OEM_UI)['original_ui_visible'])
        for xml in (DJ_UI, OEM_UI.replace('com.yeelight.cherry:id/original_content',
                                          'com.yeelight.cherry:id/loft_dj_button'), ui()):
            self.assertFalse(ident.evaluate_original_surface(snapshot, CLEAN, xml)['original_ui_visible'])
        self.assertFalse(ident.evaluate_original_surface(
            snapshot, dict(CLEAN, target_crash=True), OEM_UI)['original_ui_visible'])

    def test_full_probe_handles_warning_on_entry_and_dj_and_returns_to_classic(self):
        installed = False
        self.taps = []
        activity = 'com.yeelight.cherry.MainActivity'
        captures = iter([ui(), OEM_UI, ui(), DJ_UI, OEM_UI])
        operations = []
        def read(args, **kwargs):
            nonlocal installed, activity
            operations.append(args)
            if args[0] == 'uninstall': installed = False; return 'Success'
            if args == ['shell', 'input', 'keyevent', 'KEYCODE_BACK']:
                activity = 'com.yeelight.cherry.MainActivity'; return ''
            if args[:3] == ['shell', 'input', 'tap']:
                self.taps.append(args)
                if args[-2:] == ['30', '30']: activity = ident.DJ_ACTIVITY
                return ''
            if args == ['shell', 'dumpsys', 'activity', 'activities']:
                return 'topResumedActivity=ActivityRecord{c1 u0 com.yeelight.cherry/' + activity + ' t7}'
            if args[:3] == ['shell', 'cmd', 'package']:
                return 'com.yeelight.cherry/com.yeelight.cherry.MainActivity\n'
            if args[0] == 'logcat' or args[:3] == ['shell', 'am', 'force-stop']: return ''
            return self.read(args)
        def run(args, **kwargs):
            nonlocal installed
            if args[0] == 'install': installed = True; return 0, 'Success'
            if args[:3] == ['shell', 'am', 'start']:
                return 0, 'Status: ok\nActivity: com.yeelight.cherry/com.yeelight.cherry.MainActivity\nComplete\n'
            raise AssertionError(args)
        with patch.object(ident, 'scope', return_value='sha'), \
                patch.object(ident, 'offline_guard'), \
                patch.object(ident, 'installed_path', side_effect=lambda p: '/synthetic' if installed and p == ident.ORIGINAL else None), \
                patch.object(ident, 'verify_installed_hash'), \
                patch.object(ident, 'adb', side_effect=read), \
                patch.object(ident, 'run_adb', side_effect=run), \
                patch.object(ident, 'capture_private_hierarchy', side_effect=lambda: (next(captures), 'CAPTURED')):
            report = ident.probe(Path('/synthetic-original'), Path('/synthetic-candidate'),
                                 serial='emulator-5554', approved=True,
                                 pause=lambda _: None, probe_native_ui=True)
        self.assertTrue(report['native_dj_navigation_observed'])
        self.assertTrue(report['native_navigation_round_trip_observed'])
        self.assertEqual(len(self.taps), 3)  # warning, real DJ entry, warning
        self.assertFalse(installed)
        self.assertFalse(report['classic_functionally_accepted'])
        self.assertFalse(report['native_loft_dj_functionally_accepted'])
        self.assertFalse(any('grant' in x or 'setprop' in x or 'startservice' in x for x in operations))


if __name__ == '__main__':
    unittest.main()
