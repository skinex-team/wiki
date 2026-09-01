#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генерация patterns/*.json для wiki из гайдов korenevskiy (/tmp/guide_html).
fill-функции наполняют dict категорий; write_file пишет JSON. Конфиг — gen_config.py.
"""
import re, os, json, sys, html as H

sys.path.insert(0, '/tmp')
from fetch_guides import parse_sections, seeds_from_desc

PATTERNS_DIR = '/Users/evgenijsviridov/IdeaProjects/skinex/wiki/src/main/resources/patterns'
REPORT = []
ROMAN = {'I': 1, 'II': 2, 'III': 3, 'IV': 4, 'V': 5, 'VI': 6}

def cat(label, label_ru='', desc=''):
    return {'label': label, 'labelRu': label_ru, 'description': desc}

def slug(name):
    s = re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')
    return s or 'misc'

def load_sections(pid):
    h = open(f'/tmp/guide_html/{pid}.html', encoding='utf-8', errors='replace').read()
    return parse_sections(h)

def seed_mentions(desc):
    """#NNN упоминания + короткие числовые серии (2+) — для секций вида '#1 278, 690'."""
    from fetch_guides import _runs, _lines_tokens
    txt = re.sub(r'<[^>]+>', ' ', desc)
    out = []
    for m in re.finditer(r'#(\d{1,4})\b', txt):
        v = int(m.group(1))
        if 0 <= v <= 1000 and v not in out:
            out.append(v)
    for v in _runs(_lines_tokens(desc), 2):
        if v not in out:
            out.append(v)
    return out

TIER_RX = re.compile(r'^(?:Tier|Rank|Tire)\s*(\d+)$', re.I)  # Tire — опечатка автора в ряде гайдов
ROMAN_RX = re.compile(r'^Rank\s*(I|II|III|IV|V|VI)$', re.I)

def tier_num(title):
    m = TIER_RX.match(title)
    if m: return int(m.group(1))
    m = ROMAN_RX.match(title)
    if m: return ROMAN[m.group(1)]
    return None

def add(dst, key, seeds):
    lst = dst.setdefault(key, [])
    for s in seeds:
        if s not in lst:
            lst.append(s)

def have(pid):
    return os.path.exists(f'/tmp/guide_html/{pid}.html')

def note_missing(pids):
    return [p for p in pids if not have(p)]

# ---------- CH: '#1'→best, '#2'→tier0, RankN/TierN→tierN (default-категория) ----------
def ch_fill(categories, pids, default_key='blue_gem', default_label='Blue Gem', default_ru='Синий гем'):
    for pid in pids:
        if not have(pid):
            continue
        for title, desc in load_sections(pid):
            t = title.strip()
            n = tier_num(t)
            c = categories.setdefault(default_key, cat(default_label, default_ru))
            if t == '#1':
                add(c, 'best', [s for s in (seed_mentions(desc) or seeds_from_desc(desc)) if s != 1])
            elif t == '#2':
                add(c, 'tier0', [s for s in (seed_mentions(desc) or seeds_from_desc(desc)) if s != 2])
            elif n is not None:
                add(c, f'tier{n}', seeds_from_desc(desc))

# ---------- FEATURE: feature-секции → категории (авто-слаг), Tier/Rank → tierN ----------
def feature_fill(categories, pids, suffix='', best_too=True):
    for pid in pids:
        if not have(pid):
            continue
        cur_key = None
        for title, desc in load_sections(pid):
            t = title.strip()
            n = tier_num(t)
            if n is not None:
                if cur_key:
                    add(categories[cur_key], f'tier{n}', seeds_from_desc(desc))
                continue
            if t == '#1' and best_too:
                bg = categories.setdefault('blue_gem', cat('Blue Gem', 'Синий гем'))
                if not bg.get('labelRu'):
                    bg['labelRu'] = 'Синий гем'
                add(bg, 'best', [s for s in (seed_mentions(desc) or seeds_from_desc(desc)) if s != 1])
                cur_key = None
                continue
            if t == '#2' and best_too:
                bg = categories.setdefault('blue_gem', cat('Blue Gem', 'Синий гем'))
                if not bg.get('labelRu'):
                    bg['labelRu'] = 'Синий гем'
                add(bg, 'tier0', [s for s in (seed_mentions(desc) or seeds_from_desc(desc)) if s != 2])
                cur_key = None
                continue
            key = slug(t) + suffix
            # High Tier — витрина топа (дубли тир-листов), пропускаем БЕЗ сброса cur_key:
            # следующие Tier-секции относятся к текущей feature (перчатки, фазы).
            if key.startswith('high_tier') or key.startswith('fake_high_tier'):
                continue
            # float-секции: серии вида «0.00, 0.07, 0.15» — не паттерны, пропускаем
            if 'float' in t.lower():
                cur_key = None
                continue
            c = categories.setdefault(key, cat(t))
            cur_key = key
            seeds = seeds_from_desc(desc)
            if not seeds:
                men = seed_mentions(desc)
                seeds = men if len(men) == 1 else []
            if len(seeds) == 1:
                add(c, 'best', seeds)
            elif seeds:
                add(c, 'all', seeds)

# ---------- FI: 'Nth Max'→fire_ice tierN; 'Fake'→fake_fire_ice tier1 ----------
FI_MAX_RX = re.compile(r'^(\d+)(?:st|nd|rd|th)\s+Max', re.I)
def fi_fill(categories, pids):
    for pid in pids:
        if not have(pid):
            continue
        for title, desc in load_sections(pid):
            t = title.strip()
            m = FI_MAX_RX.match(t)
            if m:
                n = int(m.group(1))
                seeds = seeds_from_desc(desc) or seed_mentions(desc)
                add(categories.setdefault('fire_ice', cat('Fire & Ice', 'Огонь и лед')), f'tier{n}', seeds)
            elif re.match(r'^Fake', t, re.I):
                seeds = seeds_from_desc(desc)
                if seeds:
                    add(categories.setdefault('fake_fire_ice', cat('Fake Fire & Ice', 'Фейк огонь и лед')), 'tier1', seeds)

# ---------- запись ----------
# Порядок категорий в файле = приоритет при коллизии сидов (first write wins в реестре):
# сначала gem-категории и blue_gem, потом фазовые особенности.
CAT_PRIORITY = ['blue_gem', 'blue_gem_backside', 'fire_ice', 'fake_fire_ice',
                'ruby', 'sapphire', 'black_pearl', 'emerald', 'gold_gem', 'purple_gem']

def write_file(fname, skin, pids, categories, note=''):
    missing = note_missing(pids)
    categories = {k: v for k, v in categories.items()
                  if any(isinstance(x, list) and x for x in v.values())}
    if not categories:
        REPORT.append(f"!! {fname}: no data (missing={missing}), file NOT written")
        return False
    categories = dict(sorted(categories.items(),
                             key=lambda kv: (CAT_PRIORITY.index(kv[0]) if kv[0] in CAT_PRIORITY else 99, kv[0])))
    doc = {
        'skin': skin,
        'marketHashName': skin,
        'normalizedName': skin.lower(),
        'source': f'steamcommunity korenevskiy guides {pids} {note}'.strip(),
        'author': 'korenevskiy guide parser',
        'updated': '2026-09-01',
        'categories': categories,
    }
    with open(f'{PATTERNS_DIR}/{fname}.json', 'w', encoding='utf-8') as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
    total = sum(len(x) for v in categories.values() for x in v.values() if isinstance(x, list))
    flag = f" MISSING {missing}" if missing else ""
    REPORT.append(f"OK {fname}: cats={len(categories)} seeds={total}{flag}")
    return True

if __name__ == '__main__':
    import gen_config  # noqa: F401
    print('\n'.join(REPORT))
