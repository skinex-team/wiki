#!/usr/bin/env python3
"""Скачивание гайдов korenevskiy в /tmp/guide_html (кэш) + разбор секций."""
import re, html as H, json, os, sys, time, urllib.request

UA_STR = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
UA = {"User-Agent": UA_STR}
CACHE = "/tmp/guide_html"
os.makedirs(CACHE, exist_ok=True)

def fetch(pid):
    """urllib получает SSR-оболочку без контента — качаем curl'ом (классическая разметка)."""
    path = f"{CACHE}/{pid}.html"
    if os.path.exists(path) and os.path.getsize(path) > 20000:
        return open(path, encoding='utf-8', errors='replace').read()
    url = f"https://steamcommunity.com/sharedfiles/filedetails/?id={pid}"
    import subprocess
    for attempt in range(5):
        try:
            r = subprocess.run(
                ["curl", "-s", "-A", UA_STR, "--max-time", "40",
                 "-H", "Accept: text/html,application/xhtml+xml",
                 "-H", "Accept-Language: en-US,en;q=0.9", url],
                capture_output=True, timeout=50)
            h = r.stdout.decode('utf-8', 'replace')
            if 'subSection' in h or 'guide subContent' in h or 'bb_h1' in h:
                open(path, 'w', encoding='utf-8').write(h)
                return h
            print(f"  {pid}: other page ({len(h)}b), backoff", file=sys.stderr)
            time.sleep(8 + attempt * 8)
        except Exception as e:
            print(f"  {pid}: {e}, retry", file=sys.stderr)
            time.sleep(5 + attempt * 3)
    return None

def parse_sections(h):
    """[(title, desc_html)] из subSection detailBox блоков."""
    blocks = re.findall(
        r'<div class="subSection detailBox" id="\d+">\s*<div class="subSectionTitle">\s*(.*?)\s*</div>\s*<div class="subSectionDesc">(.*?)(?=<div class="subSection detailBox"|<div class="commentthread_area|$)',
        h, re.S)
    out = []
    for title, desc in blocks:
        t = H.unescape(re.sub(r'<[^>]+>', '', title))
        t = re.sub(r'\s+', ' ', t).strip()
        out.append((t, desc))
    return out

def _lines_tokens(desc):
    txt = re.sub(r'<br\s*/?>', '\n', desc)
    txt = re.sub(r'</(div|p|li|tr|table|h\d)>', '\n', txt)
    txt = re.sub(r'<[^>]+>', ' ', txt)
    txt = H.unescape(txt)
    for line in txt.split('\n'):
        yield [t for t in re.split(r'[\s,;|]+', line) if t]

def _runs(lines, min_n):
    """Серии из >= min_n подряд идущих чисел 0-1000 (дедуп, порядок сохранён)."""
    out = []
    for toks in lines:
        run = []
        for t in toks + [None]:
            if t is not None and re.fullmatch(r'\d{1,4}', t):
                run.append(int(t))
            else:
                if len(run) >= min_n:
                    for v in run:
                        if 0 <= v <= 1000 and v not in out:
                            out.append(v)
                run = []
    return out

def seeds_from_desc(desc):
    """Сид-листы: серии из 3+ чисел (разделители — пробелы/запятые/точки с запятой/пайпы)."""
    return _runs(_lines_tokens(desc), 3)

if __name__ == '__main__':
    guides = json.load(open('/tmp/guides_list.json'))
    only = set(sys.argv[1:])
    report = {}
    for pid, title in guides:
        if only and pid not in only:
            continue
        h = fetch(pid)
        if h is None:
            print(f"{pid} | {title} | FETCH FAIL")
            continue
        secs = parse_sections(h)
        report[pid] = {'title': title,
                       'sections': [{'name': t, 'seeds': seeds_from_desc(d)} for t, d in secs]}
        print(f"{pid} | {title} | " + " ; ".join(
            f"{t} [{len(seeds_from_desc(d))}]" for t, d in secs))
        time.sleep(0.6)
    json.dump(report, open('/tmp/guides_sections.json', 'w'), ensure_ascii=False, indent=1)
