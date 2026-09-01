#!/usr/bin/env python3
"""Ротатор: добирает недостающие гайды — live Steam (classic), фолбэк Wayback.
Крутит до полного набора или ~3 часов. Прогресс в stdout."""
import json, os, subprocess, sys, time

UA_STR = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
CACHE = "/tmp/guide_html"
META = "/tmp/guide_html/meta.json"
DEADLINE = time.time() + 3 * 3600

def have(pid):
    p = f"{CACHE}/{pid}.html"
    return os.path.exists(p) and os.path.getsize(p) > 20000

def curl(url, extra=()):
    r = subprocess.run(["curl", "-sL", "-A", UA_STR, "--max-time", "50",
                        "-w", "\n%{url_effective}", *extra, url],
                       capture_output=True, timeout=60)
    out = r.stdout.decode('utf-8', 'replace')
    body, _, eff = out.rpartition('\n')
    return body, eff.strip()

def good(body):
    return 'subSection' in body or 'bb_h1' in body or 'guide subContent' in body

def try_live(pid):
    body, _ = curl(f"https://steamcommunity.com/sharedfiles/filedetails/?id={pid}",
                   extra=("-H", "Accept: text/html,application/xhtml+xml",
                          "-H", "Accept-Language: en-US,en;q=0.9"))
    return body if good(body) else None

def try_wayback(pid):
    body, eff = curl(f"http://web.archive.org/web/2026/https://steamcommunity.com/sharedfiles/filedetails/?id={pid}")
    return (body, eff) if good(body) else (None, None)

if __name__ == '__main__':
    guides = json.load(open('/tmp/guides_list.json'))
    meta = json.load(open(META)) if os.path.exists(META) else {}
    round_n = 0
    while time.time() < DEADLINE:
        missing = [(pid, t) for pid, t in guides if not have(pid)]
        if not missing:
            print("ALL DONE")
            break
        round_n += 1
        print(f"--- round {round_n}: missing {len(missing)}", flush=True)
        got_this_round = 0
        for pid, title in missing:
            body = try_live(pid)
            src = 'live'
            if body is None:
                body, eff = try_wayback(pid)
                src = 'wayback'
                if body is not None:
                    meta[pid] = {'wayback': eff}
            if body is not None:
                open(f"{CACHE}/{pid}.html", 'w', encoding='utf-8').write(body)
                got_this_round += 1
                print(f"OK({src}) {pid} | {title}", flush=True)
            time.sleep(2.5)
        json.dump(meta, open(META, 'w'), indent=0)
        if got_this_round == 0:
            print("no progress this round, sleeping 120s", flush=True)
            time.sleep(120)
    left = [(pid, t) for pid, t in guides if not have(pid)]
    print(f"finished: missing {len(left)}")
    for pid, t in left:
        print(f"LEFT {pid} | {t}")
