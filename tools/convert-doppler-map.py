#!/usr/bin/env python3
"""
Convert doppler-pattern-map.json (pricempire guide scrape, 2026-08-27) into the
per-skin pattern files in src/main/resources/patterns/.

Only knives with real scraped seed lists are written. Knives marked
"generic": true in the map, or whose entries contain only notes / template
references ("see Karambit P4 template" style strings instead of seed lists),
are left fully untouched.

Rules (see task brief):
- Phase-variant tier lists (encoded keys Tier(\\d+)_.., BTA_.., OtherBTA_..,
  optionally prefixed like GreenDiamond_Tier1_48) become seed categories:
  best (mirror of the top tier list) + tier1..tierN, BTA/OtherBTA become the
  tiers after the numbered ones. Category key = snake_case variant name +
  _p<phase>, e.g. "Phase1.Fake Black Pearl" -> fake_black_pearl_p1,
  "Phase2.Max Pink / Pink Galaxy" -> max_pink_p2.
- Gem entries (Ruby/Sapphire/Black Pearl/Emerald) only get their description
  updated from the map note/market_note (gems use tier + isBest, no seed
  lists). Exception: if a skin has NO phase-variant seed data at all but a gem
  entry carries real rank lists (Glock-18 Emerald), the rank lists are mapped
  onto the gem category as best/tier1..tierN.
- Pre-existing seed lists that are not backed by the map (generic copy-pasted
  fake_black_pearl_p1 seeds) are stripped from written files.
- Seeds are deduped within a category (first occurrence wins across
  tier1..tierN; best mirrors tier1 by convention) and no seed may appear in
  two categories of the same file (first category in phase order wins; drops
  are reported).
- Written files get source = guide_urls joined, author = "pricempire
  converter", updated = "2026-08-30".

Usage: python3 tools/convert-doppler-map.py   (writes in place, prints report)
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAP_FILE = ROOT / "doppler-pattern-map.json"
PATTERNS_DIR = ROOT / "src" / "main" / "resources" / "patterns"

AUTHOR = "pricempire converter"
UPDATED = "2026-08-30"

# Encoded tier-list keys, e.g.:
#   Tier1_Excellent_26_seeds / Tier1_47 / Tier1_Best_47_seeds / Tier1_47_fakeBlackPearl
#   Rank1_Pure_39_seeds_partial / Rank1_26_seeds
#   BTA_Good_31_seeds / BTA_48 / OtherBTA_53_seeds / OtherBTA_35
#   GreenDiamond_Tier1_48 (prefix groups a sub-variant)
KEY_RE = re.compile(
    r"^(?:(?P<prefix>[A-Za-z]+)_)?(?P<kind>Tier|Rank|OtherBTA|BTA)(?P<num>\d+)?(?:_(?P<rest>.*))?$"
)
PHASE_RE = re.compile(r"^Phase(\d+)$")

GEM_KEYS = {
    "Ruby": "ruby",
    "Sapphire": "sapphire",
    "Black Pearl": "black_pearl",
    "Emerald": "emerald",
    "Emerald Gamma": "emerald",
}

SEED_FIELDS = ["best", "all", "all_desc"] + [f"tier{i}" for i in range(11)]


def is_seed_list(value):
    return (
        isinstance(value, list)
        and len(value) > 0
        and all(isinstance(x, int) and not isinstance(x, bool) for x in value)
    )


def parse_tier_key(key):
    """Return (prefix, sort_order, tier_num_or_None) for encoded keys, else None."""
    m = KEY_RE.match(key)
    if not m:
        return None
    kind = m.group("kind")
    prefix = m.group("prefix")
    num = int(m.group("num")) if m.group("num") else None
    if kind in ("Tier", "Rank"):
        if num is None:
            return None
        order = (0, num)
    elif kind == "BTA":
        order = (1, 0)
    else:  # OtherBTA comes last
        order = (2, 0)
    return prefix, order, num


def split_camel(name):
    return re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name)


def variant_naming(raw_name, phase_num):
    """'Max Pink / Pink Galaxy' -> ('Max Pink', 'max_pink_p2', 'Max Pink P2')."""
    name = re.sub(r"_\d+_patterns$", "", raw_name)          # Fake Black Pearl_199_patterns
    name = re.sub(r"\s*\(.*?\)", "", name).strip()          # Fake Black Pearl (in P3)
    name = split_camel(name)
    first = name.split("/")[0].strip()
    slug = re.sub(r"[^a-z0-9]+", "_", first.lower()).strip("_")
    return first, f"{slug}_p{phase_num}", f"{first} P{phase_num}"


def extract_groups(variant_dict, skipped_refs):
    """Split a variant dict into {prefix_or_None: [(src_key, [seeds]), ...]} ordered tier lists."""
    groups = {}
    for k, v in variant_dict.items():
        if is_seed_list(v):
            parsed = parse_tier_key(k)
            if parsed is None:
                skipped_refs.append(f"{k} (list with unparseable key)")
                continue
            prefix, order, _ = parsed
            groups.setdefault(prefix, []).append((order, k, v))
        elif isinstance(v, str) and k not in ("note",):
            skipped_refs.append(k)
    ordered = {}
    for prefix, items in groups.items():
        # stable sort by tier order; keep original position within same tier
        items.sort(key=lambda t: t[0])
        ordered[prefix] = [(k, v) for _, k, v in items]
    return ordered


def dedupe_lists(tier_lists, used_global, report):
    """Dedupe within lists, across the category's tiers, and against used_global.
    Returns (ordered surviving lists, is_top_tier_marked)."""
    cat_seen = set()
    out = []
    for src_key, seeds in tier_lists:
        surviving = []
        within = cross = 0
        for s in seeds:
            if s in cat_seen:
                within += 1
                continue
            if s in used_global:
                cross += 1
                continue
            cat_seen.add(s)
            surviving.append(s)
        if within or cross:
            parts = []
            if within:
                parts.append(f"{within} within-category")
            if cross:
                parts.append(f"{cross} cross-category")
            report.append(f"    {src_key}: dropped {' + '.join(parts)} dup(s), kept {len(surviving)}/{len(seeds)}")
        out.append((src_key, surviving))
    first = tier_lists[0][0] if tier_lists else ""
    top_marked = bool(re.match(r"^(?:[A-Za-z]+_)?(?:Tier|Rank)1(?:_|$)", first))
    return out, top_marked


def build_seed_category(label, label_ru, description, deduped, top_marked, report):
    cat = {"label": label, "labelRu": label_ru, "description": description}
    lists = [(k, s) for k, s in deduped if s]
    dropped_empty = [k for k, s in deduped if not s]
    for k in dropped_empty:
        report.append(f"    tier list emptied by dedupe, omitted: {k}")
    if not lists:
        return cat, {}
    counts = {}
    tier_no = 0
    for i, (src_key, seeds) in enumerate(lists):
        tier_no += 1
        field = f"tier{tier_no}"
        cat[field] = seeds
        counts[field] = len(seeds)
        if i == 0 and top_marked:
            cat["best"] = list(seeds)  # mirror of the top tier, per existing convention
    # reorder: best right after description
    if "best" in cat:
        ordered = {"label": cat["label"], "labelRu": cat["labelRu"], "description": cat["description"], "best": cat["best"]}
        for f, seeds in ((f"tier{i}", cat[f"tier{i}"]) for i in range(1, tier_no + 1)):
            ordered[f] = seeds
        cat = ordered
    return cat, counts


def strip_seed_fields(cat):
    removed = []
    for f in SEED_FIELDS:
        if f in cat:
            removed.append(f)
            del cat[f]
    return removed


def main():
    pattern_files = {}
    for p in sorted(PATTERNS_DIR.glob("*.json")):
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except Exception as e:
            print(f"WARN: cannot parse {p.name}: {e}")
            continue
        for key in (data.get("marketHashName"), data.get("skin")):
            if key:
                pattern_files[key] = (p, data)
                # guns have no "★ " prefix in files but the scrape map adds one
                stripped = key.removeprefix("★ ").strip()
                pattern_files.setdefault(stripped, (p, data))
                pattern_files.setdefault(f"★ {stripped}", (p, data))

    doppler_map = json.loads(MAP_FILE.read_text(encoding="utf-8"))

    written, skipped = [], []
    for skin, entry in doppler_map.items():
        if skin == "meta":
            continue
        if skin not in pattern_files:
            skipped.append((skin, "no matching per-skin file"))
            continue
        path, doc = pattern_files[skin]

        if entry.get("generic") is True:
            skipped.append((skin, "generic: true fallback (no per-knife data)"))
            continue

        guide_urls = entry.get("guide_urls") or []
        report = []
        categories = doc.get("categories", {})
        used_global = set()
        touched = set()   # category keys regenerated from real data
        wrote_seeds = False

        # ---- phase variants -------------------------------------------------
        new_cats = []  # (cat_key, cat_dict, counts)
        phase_nums = sorted(
            (int(PHASE_RE.match(k).group(1)) for k in entry if PHASE_RE.match(k))
        )
        for n in phase_nums:
            phase = entry[f"Phase{n}"]
            if not isinstance(phase, dict):
                continue
            phase_note = phase.get("note") if isinstance(phase.get("note"), str) else None
            skipped_refs = []
            for var_name, var_val in phase.items():
                if not isinstance(var_val, dict):
                    if isinstance(var_val, str) and var_name not in ("note", "guide", "generic"):
                        skipped_refs.append(var_name)
                    continue
                groups = extract_groups(var_val, skipped_refs)
                var_note = var_val.get("note") if isinstance(var_val.get("note"), str) else None
                for prefix, tier_lists in groups.items():
                    display, cat_key, label = variant_naming(prefix or var_name, n)
                    description = var_note or phase_note or ""
                    if not description and cat_key in categories:
                        description = categories[cat_key].get("description", "")
                    if not description:
                        description = var_name.strip()
                    deduped, top_marked = dedupe_lists(tier_lists, used_global, report)
                    for _, seeds in deduped:
                        used_global.update(seeds)
                    if cat_key in categories:
                        old = categories[cat_key]
                        label_ru = old.get("labelRu", "")
                        label = old.get("label", label)
                    else:
                        label_ru = ""
                    cat, counts = build_seed_category(label, label_ru, description, deduped, top_marked, report)
                    new_cats.append((cat_key, cat, counts))
                    touched.add(cat_key)
                    if counts:
                        wrote_seeds = True
            for r in skipped_refs:
                report.append(f"    reference-only (no seeds imported): {r}")

        # ---- gem entries -----------------------------------------------------
        gem_updates = []
        gem_rank_data = None  # (cat_key, tier_lists, description) fallback for skins without variant seeds
        gem_rank_all = []     # every gem rank list found, for reporting
        for gem_name, cat_key in GEM_KEYS.items():
            gem = entry.get(gem_name)
            if not isinstance(gem, dict):
                continue
            note = gem.get("note") or gem.get("market_note")
            rank_lists = [(k, v) for k, v in gem.items() if is_seed_list(v) and parse_tier_key(k)]
            rank_lists.sort(key=lambda t: parse_tier_key(t[0])[1])
            if rank_lists:
                gem_rank_all.append((gem_name, rank_lists))
            if cat_key in categories:
                if note:
                    categories[cat_key]["description"] = note
                    gem_updates.append(cat_key)
                if rank_lists and gem_rank_data is None:
                    gem_rank_data = (cat_key, rank_lists, note)
            elif cat_key == "emerald" and "gamma doppler" in doc.get("normalizedName", ""):
                # create the emerald gem category for gamma files being written
                pi = gem.get("paint_index")
                hint = gem.get("best") if isinstance(gem.get("best"), str) else None
                desc = note or (f"paint index {pi}" + (f". {hint}" if hint else "") if pi else "")
                categories[cat_key] = {
                    "label": "Emerald",
                    "labelRu": "",
                    "description": desc,
                    "tier": 1,
                    "isBest": True,
                }
                gem_updates.append("emerald (new)")
                if rank_lists and gem_rank_data is None:
                    gem_rank_data = (cat_key, rank_lists, desc)

        # gem seed fallback: only when the skin has no phase-variant seeds at all
        if gem_rank_data and not wrote_seeds:
            cat_key, rank_lists, desc = gem_rank_data
            deduped, top_marked = dedupe_lists(rank_lists, used_global, report)
            for _, seeds in deduped:
                used_global.update(seeds)
            base = categories[cat_key]
            cat, counts = build_seed_category(
                base.get("label", "Emerald"), base.get("labelRu", ""), base.get("description", ""),
                deduped, top_marked, report,
            )
            # keep gem style fields
            merged = {"label": cat["label"], "labelRu": cat["labelRu"], "description": cat["description"],
                      "tier": base.get("tier", 1), "isBest": base.get("isBest", True)}
            for f in cat:
                if f not in merged:
                    merged[f] = cat[f]
            new_cats.append((cat_key, merged, counts))
            touched.add(cat_key)
            if counts:
                wrote_seeds = True
                gem_updates.append(f"{cat_key} +rank seeds")
                gem_rank_all = [g for g in gem_rank_all if g[1] is not rank_lists]
        for gem_name, rank_lists in gem_rank_all:
            report.append(
                f"    gem rank lists not mapped (gems use tier/isBest, seed lists only as fallback): "
                f"{gem_name}: " + ", ".join(f"{k} ({len(v)} seeds)" for k, v in rank_lists)
            )

        if not wrote_seeds:
            reason = "no real seed lists (notes/template references only)"
            skipped.append((skin, reason))
            continue

        # ---- strip legacy copy-pasted seed lists not backed by the map -------
        stripped = []
        for cat_key, cat in categories.items():
            if cat_key in touched or not isinstance(cat, dict):
                continue
            removed = strip_seed_fields(cat)
            if removed:
                stripped.append(f"{cat_key} ({', '.join(removed)})")

        # ---- apply new/updated categories ------------------------------------
        for cat_key, cat, _counts in new_cats:
            categories[cat_key] = cat

        # ---- invariant check: no seed in two categories -----------------------
        seen = {}
        conflicts = []
        for cat_key, cat in categories.items():
            if not isinstance(cat, dict):
                continue
            for f in SEED_FIELDS:
                for s in cat.get(f) or []:
                    if s in seen and seen[s] != cat_key:
                        conflicts.append((s, seen[s], cat_key))
                    seen.setdefault(s, cat_key)
        if conflicts:
            print(f"ERROR: {skin}: cross-category conflicts remain: {conflicts[:10]}")
            sys.exit(1)

        doc["source"] = ", ".join(guide_urls)
        doc["author"] = AUTHOR
        doc["updated"] = UPDATED

        path.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
        written.append((skin, path.name, new_cats, gem_updates, stripped, report))

    # ---- report --------------------------------------------------------------
    print("=" * 78)
    print("WRITTEN (real data):")
    for skin, fname, new_cats, gem_updates, stripped, report in written:
        print(f"\n{skin} -> {fname}")
        for cat_key, _cat, counts in new_cats:
            if counts:
                detail = " ".join(f"{f}={n}" for f, n in counts.items())
                print(f"  {cat_key}: {detail} (total {sum(counts.values())})")
            else:
                print(f"  {cat_key}: no surviving seeds (description only)")
        if gem_updates:
            print(f"  gem descriptions updated: {', '.join(gem_updates)}")
        if stripped:
            print(f"  stripped legacy copy-pasted seed lists: {', '.join(stripped)}")
        for line in report:
            print(line)
    print("\n" + "=" * 78)
    print("SKIPPED (files left untouched):")
    for skin, reason in skipped:
        print(f"  {skin}: {reason}")
    print(f"\n{len(written)} files written, {len(skipped)} skipped.")


if __name__ == "__main__":
    main()
