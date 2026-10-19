"""Short read-only CPU/frame baseline including this app's isolated WebView renderer.

100% CPU = one fully occupied logical core, not the whole phone.
Does not reset battery stats, simulate unplugging, or change system settings.
"""
import argparse, json, re, statistics, subprocess, time
from pathlib import Path

def adb(*args):
    return subprocess.check_output(['adb', 'shell', *args], text=True, encoding='utf-8', errors='replace')

parser = argparse.ArgumentParser()
parser.add_argument('label')
parser.add_argument('--seconds', type=int, default=30)
args = parser.parse_args()
out = Path('shots/perf') / args.label
out.mkdir(parents=True, exist_ok=True)
pid = int(adb('pidof', 'com.wirelesspet').split()[0])
processes = adb('dumpsys', 'activity', 'processes')
main = re.search(r'ProcessRecord\{[^\n]* '+str(pid)+r':com\.wirelesspet/u0a(\d+)', processes)
assert main, 'Cannot resolve app UID'
renderers = sorted(set(int(n) for n in re.findall(r'ProcessRecord\{[^\n]* (\d+):com\.google\.android\.webview:[^\n]*/u0a'+main[1]+r'i\d+', processes)))
pids = [pid] + renderers
battery_before = adb('dumpsys', 'battery')
adb('dumpsys', 'gfxinfo', 'com.wirelesspet', 'reset')
start = time.monotonic()
raw = adb('top', '-b', '-d', '1', '-n', str(args.seconds + 1), '-p', ','.join(map(str, pids)))
elapsed = time.monotonic() - start
frames = adb('dumpsys', 'gfxinfo', 'com.wirelesspet')
battery_after = adb('dumpsys', 'battery')
blocks = re.split(r'Tasks:', raw)[1:]
samples = []
for block in blocks[1:]:  # discard top's first instantaneous sample
    row = {str(p): 0.0 for p in pids}
    for line in block.splitlines():
        cols = line.split()
        if len(cols) >= 11 and cols[0] in row:
            row[cols[0]] = float(cols[8].strip('%'))
    samples.append(row)
assert samples, 'No top samples parsed'
totals = [sum(row.values()) for row in samples]
summary = dict(label=args.label, seconds=round(elapsed, 2), samples=len(samples), pids=pids,
    cpu_percent_of_one_core=round(statistics.mean(totals), 2),
    process_cpu={str(p):round(statistics.mean(row[str(p)] for row in samples), 2) for p in pids},
    cpu_p95=sorted(totals)[int((len(totals)-1)*.95)],
    frame_summary=[line.strip() for line in frames.splitlines() if any(k in line for k in
        ['Total frames rendered:', 'Janky frames:', '50th percentile:', '95th percentile:', '95th gpu percentile:'])],
    battery_before=[l.strip() for l in battery_before.splitlines() if any(k in l for k in ['powered:', 'temperature:', 'level:', 'status:'])],
    battery_after=[l.strip() for l in battery_after.splitlines() if any(k in l for k in ['powered:', 'temperature:', 'level:', 'status:'])])
(out/'top.txt').write_text(raw, encoding='utf-8')
(out/'gfxinfo.txt').write_text(frames, encoding='utf-8')
(out/'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps(summary, indent=2))
