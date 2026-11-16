#!/usr/bin/env python3
"""Drive the app through the accessibility tree: ui.py texts | tap <text> | media"""
import re, subprocess, sys, html
def sh(*a): return subprocess.run(a, capture_output=True, text=True).stdout
def nodes():
    sh('adb','shell','uiautomator','dump','/sdcard/ui.xml')
    x = sh('adb','shell','cat','/sdcard/ui.xml')
    out = []
    for n in re.findall(r'<node [^>]*>', x):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', n))
        b = list(map(int, re.findall(r'\d+', a.get('bounds',''))))
        if len(b) == 4: out.append((html.unescape(a.get('text') or a.get('content-desc') or ''), a.get('clickable') == 'true', b))
    return out
cmd = sys.argv[1]
if cmd == 'texts':
    for t, c, b in nodes():
        if t: print(f'{t[:60]:62} {b}')
elif cmd == 'tap':
    want = sys.argv[2]
    hit = [b for t, c, b in nodes() if want in t]
    if not hit: sys.exit(f'tap: "{want}" not on screen')
    b = hit[int(sys.argv[3]) if len(sys.argv) > 3 else 0]
    sh('adb','shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2)); print('tapped', want, b)
elif cmd == 'media':
    d = sh('adb','shell','dumpsys','media_session')
    m = re.search(r'state=PlaybackState \{state=(\w+)\(\d+\), position=(\d+), buffered position=(\d+)', d)
    n = re.search(r'metadata: size=\d+, description=(.*)', d)
    print(m.groups() if m else None, '|', n.group(1)[:70] if n else None)
