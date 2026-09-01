#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Чистка шаблонных копипаст-категорий в doppler/gamma JSON.

Проблема: автор гайдов копирует одни и те же тир-листы (47/53/56/59 сидов)
в гайды разных ножей и разных категорий. Такие списки фиктивны.

Правила:
1. Шаблон: tier-лист (>=10 сидов), встречающийся в 2+ категориях (в любом doppler-файле)
   -> все категории с таким листом удаляются.
2. Пересечение гем-категорий (ruby/sapphire/black_pearl/emerald) внутри файла >= 3 сидов
   -> оставляется категория с наибольшим суммарным числом сидов, остальные удаляются
   (один сид не может быть одновременно ruby и sapphire — авторская копипаста).

Запуск: python3 clean_doppler_templates.py [--apply]
Без --apply — только отчёт.
"""
import json, glob, os, sys

PATTERNS_DIR = '/Users/evgenijsviridov/IdeaProjects/skinex/wiki/src/main/resources/patterns'
GEM = ['ruby', 'sapphire', 'black_pearl', 'emerald']
MIN_TEMPLATE = 10
MIN_GEM_OVERLAP = 3
TIER_KEYS = ('tier0', 'tier1', 'tier2', 'tier3', 'tier4', 'tier5', 'tier6', 'best', 'all')

def tier_lists(cat):
    return [(k, v) for k, v in cat.items() if k in TIER_KEYS and isinstance(v, list)]

def all_seeds(cat):
    out = set()
    for _, v in tier_lists(cat):
        out.update(v)
    return out

def main(apply):
    files = sorted(glob.glob(f'{PATTERNS_DIR}/*doppler*.json'))
    docs = {f: json.load(open(f)) for f in files}

    # 1. глобальные шаблоны: хэш списка -> [(file, category)]
    by_hash = {}
    for f, d in docs.items():
        for cid, cat in d.get('categories', {}).items():
            for tk, seeds in tier_lists(cat):
                if len(seeds) >= MIN_TEMPLATE:
                    by_hash.setdefault(tuple(seeds), []).append((f, cid, tk))

    template_cats = {}  # file -> {category: reason}
    for seeds, occ in by_hash.items():
        cats = {(f, c) for f, c, _ in occ}
        if len(cats) >= 2:
            for f, c, tk in occ:
                template_cats.setdefault(f, {}).setdefault(c, []).append(
                    f'template list ({len(seeds)} seeds) == {tk} of ' +
                    ', '.join(sorted(f'{os.path.basename(ff)}:{cc}' for ff, cc in cats if ff != f or cc != c)))

    # 2. пересечения гемов внутри файла (среди выживших)
    for f, d in docs.items():
        cats = d.get('categories', {})
        gems = [g for g in GEM if g in cats and g not in template_cats.get(f, {})]
        removed = set()
        for i in range(len(gems)):
            for j in range(i + 1, len(gems)):
                a, b = gems[i], gems[j]
                if a in removed or b in removed:
                    continue
                inter = all_seeds(cats[a]) & all_seeds(cats[b])
                if len(inter) >= MIN_GEM_OVERLAP:
                    ka, kb = len(all_seeds(cats[a])), len(all_seeds(cats[b]))
                    drop, keep = (b, a) if ka >= kb else (a, b)
                    template_cats.setdefault(f, {})[drop] = [
                        f'gem overlap with {keep}: {len(inter)} shared seeds']
                    removed.add(drop)

    # отчёт + запись
    total_removed = 0
    for f, d in docs.items():
        reasons = template_cats.get(f)
        if not reasons:
            continue
        cats = d['categories']
        dropped = [cid for cid in sorted(reasons) if cid in cats]
        if not dropped:
            continue
        print(f'== {os.path.basename(f)}: DROP {len(dropped)} | KEEP {len(cats) - len(dropped)}')
        for cid in dropped:
            n = len(all_seeds(cats[cid]))
            r = reasons[cid]
            r0 = r[0] if isinstance(r, list) else r
            print(f'   - {cid} ({n} seeds): {r0[:110]}')
            if apply:
                del cats[cid]
            total_removed += 1
        if apply:
            if cats:
                json.dump(d, open(f, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
            else:
                os.remove(f)
                print(f'   (файл удалён целиком — категорий не осталось)')
    print(f'\n{"APPLIED" if apply else "DRY-RUN"}: {total_removed} categories removed')

if __name__ == '__main__':
    main('--apply' in sys.argv)
