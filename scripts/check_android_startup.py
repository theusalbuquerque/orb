"""Cold-start both installed variants and fail on verifier/crash/foreground loss."""
import subprocess
import time
from pathlib import Path

PACKAGE = 'com.music.orb'
OUTPUT = Path('startup-diagnostics')
OUTPUT.mkdir(exist_ok=True)


def adb(*args, check=True):
    return subprocess.run(['adb', *args], check=check, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout


for flavor in ('dev', 'prod'):
    apk = Path(f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk')
    adb('uninstall', PACKAGE, check=False)
    print(adb('install', str(apk)), flush=True)
    adb('logcat', '-c')
    try:
        result = adb('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/.MainActivity')
        print(result, flush=True)
        if 'Error:' in result or 'Status: ok' not in result:
            raise RuntimeError(f'{flavor}: launcher failed')
        initial_pid = None
        for _ in range(5):
            time.sleep(3)
            pid = adb('shell', 'pidof', PACKAGE, check=False).strip()
            if not pid:
                raise RuntimeError(f'{flavor}: process exited after launch')
            if initial_pid is not None and pid != initial_pid:
                raise RuntimeError(f'{flavor}: process restarted unexpectedly')
            initial_pid = pid
        activities = adb('shell', 'dumpsys', 'activity', 'activities')
        (OUTPUT / f'{flavor}-activities.txt').write_text(activities)
        if not any('ResumedActivity' in line and f'{PACKAGE}/.MainActivity' in line
                   for line in activities.splitlines()):
            raise RuntimeError(f'{flavor}: MainActivity is not resumed')
        crashes = adb('logcat', '-b', 'crash', '-d')
        if PACKAGE in crashes or 'VerifyError' in crashes:
            raise RuntimeError(f'{flavor}: crash log is not empty:\n{crashes}')
        with (OUTPUT / f'{flavor}-startup.png').open('wb') as screenshot:
            subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=screenshot, check=True)
        print(f'PASS: {flavor} cold launch remains alive and resumed for 15 seconds', flush=True)
    finally:
        (OUTPUT / f'{flavor}-logcat.txt').write_text(adb('logcat', '-d'))
