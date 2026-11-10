#!/usr/bin/env python3
"""Exercise a final APK's real process startup on an explicitly selected emulator.

Physical devices are refused. --clean-data resets only this app on that emulator.
Every setup/check must succeed; an already running Activity is not a cold-start pass.
"""
import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--clean-data', action='store_true')
p.add_argument('--launches', type=int, default=3)
a = p.parse_args()
package = 'com.recordroom.vinyl'
base = [a.adb, '-s', a.serial]


def run(*args, timeout=40):
    return subprocess.run(base + list(args), capture_output=True, text=True, timeout=timeout)


def checked(*args, timeout=40):
    result = run(*args, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args} failed ({result.returncode}): {result.stdout}{result.stderr}')
    return result.stdout


if not a.serial.startswith('emulator-') or checked('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
    raise SystemExit('This runner only operates on an explicitly selected emulator.')
if not 1 <= a.launches <= 10:
    raise SystemExit('Use 1-10 launches')
a.out.mkdir(parents=True, exist_ok=True)
report = {
    'apk_sha256': hashlib.sha256(a.apk.read_bytes()).hexdigest(),
    'serial': a.serial, 'emulator': True, 'physical_device_test': False,
    'clean_data': a.clean_data, 'launches': [], 'passed': False,
}
try:
    for key, prop in [('device_model', 'ro.product.model'), ('android_release', 'ro.build.version.release'), ('api', 'ro.build.version.sdk')]:
        report[key] = checked('shell', 'getprop', prop).strip()
    report['install_output'] = checked('install', '-r', str(a.apk), timeout=90)
    if 'Success' not in report['install_output']:
        raise RuntimeError('APK install did not report Success')
    if a.clean_data and 'Success' not in checked('shell', 'pm', 'clear', package):
        raise RuntimeError('Could not reset emulator app state')
    for n in range(a.launches):
        checked('shell', 'am', 'force-stop', package)
        stopped = run('shell', 'pidof', package)
        # pidof returns 1 when no matching process exists. Any other error is inconclusive.
        if stopped.returncode != 1 or stopped.stdout.strip() or stopped.stderr.strip():
            raise RuntimeError('App process was not confirmed stopped before launch')
        checked('logcat', '-b', 'crash', '-c')
        launched = checked('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
        if 'Status: ok' not in launched or 'Warning:' in launched or 'Error:' in launched:
            raise RuntimeError('Activity did not start cleanly: ' + launched)
        time.sleep(3)
        pid_result = run('shell', 'pidof', package)
        pid = pid_result.stdout.strip()
        crash = checked('logcat', '-b', 'crash', '-d')
        state = checked('shell', 'dumpsys', 'activity', 'activities')
        resumed = [line.strip() for line in state.splitlines()
                   if ('topResumedActivity' in line or 'mResumedActivity' in line) and package in line]
        bad = package in crash and ('FATAL EXCEPTION' in crash or 'Fatal signal' in crash)
        passed = pid_result.returncode == 0 and bool(pid) and bool(resumed) and not bad
        entry = {'number': n + 1, 'confirmed_stopped_before_launch': True, 'pid': pid,
                 'resumed': resumed, 'launch_output': launched, 'crash': bad, 'passed': passed}
        report['launches'].append(entry)
        (a.out / f'launch-{n + 1}-crash.log').write_text(crash)
        print(json.dumps(entry, ensure_ascii=False), flush=True)
        if not passed:
            break
    report['passed'] = len(report['launches']) == a.launches and all(x['passed'] for x in report['launches'])
except (RuntimeError, subprocess.TimeoutExpired) as error:
    report['error'] = str(error)
    print(str(error), flush=True)
finally:
    (a.out / 'startup-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
raise SystemExit(0 if report['passed'] else 1)
