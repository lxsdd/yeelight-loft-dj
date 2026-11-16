"""Read-only API37 runtime contract, without APKs, signing or OEM data.

Runs on the existing public disposable emulator. Reads real framework strings,
page size and window grammar before the owner needs another private OEM test.
The message hierarchy used to test rendering is synthetic, NOT a visible UI
acceptance or a proof that the original overlay was a page-size dialog.
"""
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

from page_size_warning import (RESOURCES, rendered_resource, _system_phone_window,
                               warning_action, read_framework_resources)


def check_contract(run):
    report = {'status': 'PUBLIC_WARNING_CONTRACT_BLOCKED', 'oem_accessed': False,
              'apk_installed': False, 'ui_action_taken': False,
              'real_warning_ui_tested': False}
    def read(args, stage):
        report['blocked_at'] = stage
        try:
            return run(args)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            detail = str(getattr(error, 'stderr', '') or '')
            report['read_failure'] = ('RESOURCE_NOT_FOUND' if 'failed to get the resource' in detail
                else 'PERMISSION_DENIED' if 'Permission' in detail or 'SecurityException' in detail
                else 'UNSUPPORTED_COMMAND' if 'Unknown command' in detail
                else 'COMMAND_FAILED')
            return None
    page = read(['shell', 'getconf', 'PAGE_SIZE'], 'READ_PAGE_SIZE')
    if page is None:
        return report
    page = page.strip()
    report['kernel_page_size'] = '16K' if page == '16384' else '4K' if page == '4096' else 'OTHER'
    report['blocked_at'] = 'PAGE_SIZE'
    if page != '16384':
        return report
    focus = read(['shell', 'dumpsys', 'window', 'displays'], 'READ_FOCUS')
    if focus is None:
        return report
    windows = read(['shell', 'dumpsys', 'window', 'windows'], 'READ_WINDOWS')
    if windows is None:
        return report
    evidence = {}
    _system_phone_window(focus, windows, evidence)
    report['window_parser'] = evidence
    report['blocked_at'] = 'LIVE_WINDOW_GRAMMAR'
    if (evidence.get('window_binding') != 'MATCHED'
            or evidence.get('window_owner') == 'UNPARSEABLE'
            or evidence.get('window_type') == 'UNPARSEABLE'):
        return report
    report['blocked_at'] = 'FRAMEWORK_RESOURCES'
    resources = read_framework_resources(run, report)
    available = [name for name in RESOURCES if name in resources]
    if 'ok' not in resources or not available:
        return report
    report['required_framework_resources_readable'] = True
    report['available_warning_variants'] = [RESOURCES[name] for name in available]
    # Synthetic system dialog fixture with the actual runtime's localized
    # resources. Never feed these synthetic coordinates into an input command.
    simulated_focus = ('mCurrentFocus=Window{01 u0 Synthetic}\n'
                       'mFocusedApp=ActivityRecord{02 u0 com.yeelight.cherry/.Synthetic t1}\n')
    simulated_window = ('Window #0 Window{01 u0 Synthetic}:\n'
                        ' mOwnerUid=1000 package=android\n'
                        ' mAttrs={ty=PHONE}\n')
    for name in available:
        root = ET.Element('hierarchy')
        ET.SubElement(root, 'node', {'package': 'android', 'resource-id': 'android:id/message',
                                    'text': rendered_resource(resources[name])})
        ET.SubElement(root, 'node', {'package': 'android', 'resource-id': 'android:id/button1',
                                    'text': rendered_resource(resources['ok']),
                                    'class': 'android.widget.Button', 'enabled': 'true',
                                    'clickable': 'true', 'bounds': '[10,10][50,50]'})
        xml = '<?xml version="1.0"?>' + ET.tostring(root, encoding='unicode')
        action = warning_action(xml, simulated_focus, simulated_window,
                                'Physical size: 1080x2400', page, resources)
        report['blocked_at'] = 'RESOURCE_RENDERING'
        if action is None or action[1] != RESOURCES[name]:
            return report
    report['synthetic_resource_rendering'] = 'PASS'
    report['status'] = 'PUBLIC_WARNING_RUNTIME_CONTRACT_PASS'
    del report['blocked_at']
    return report


def main():
    if (sys.argv[1:] != ['--no-oem'] or
            os.environ.get('GITHUB_REPOSITORY') != 'lxsdd/yeelight-loft-dj' or
            os.environ.get('GITHUB_ACTOR') != 'lxsdd' or
            os.environ.get('GITHUB_EVENT_NAME') != 'pull_request' or
            os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted'):
        print('PUBLIC_WARNING_CONTRACT_CONTEXT_DENIED')
        return 2
    def run(args):
        result = subprocess.run(['adb', '-s', 'emulator-5554', *args],
                                capture_output=True, text=True, timeout=30, check=True)
        if len(result.stdout) > 2_000_000:
            raise ValueError('bounded runtime response required')
        return result.stdout
    try:
        if (run(['shell', 'getprop', 'ro.kernel.qemu']).strip() != '1' or
                run(['shell', 'getprop', 'ro.build.version.sdk']).strip() != '37'):
            raise ValueError('exact emulator required')
        report = check_contract(run)
    except (OSError, ValueError, subprocess.SubprocessError):
        report = {'status': 'PUBLIC_WARNING_CONTRACT_READ_FAILED', 'ui_action_taken': False}
    print(json.dumps(report, sort_keys=True))
    return 0 if report['status'] == 'PUBLIC_WARNING_RUNTIME_CONTRACT_PASS' else 2


if __name__ == '__main__':
    sys.exit(main())
