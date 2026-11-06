# 01 — Pattern guide coverage audit

## Аудит источника

- Сопоставлены 302 гайда korenevskiy с ID в `source` всех pattern JSON.
- 178 ID уже были использованы; 124 не были referenced. Среди них общие hub-статьи,
  уже покрытые Fade-гайды, несвязанные Agents/Charms и Doppler/Gamma-ресурсы.
- Найденный `tools/guide-parser/gen_config.py` уже содержал ID недостающих Doppler/Gamma,
  но шаблонные списки нельзя было просто вернуть: ранее они были удалены намеренно.

## Добавление

После извлечения таблиц и повторной `clean_doppler_templates` очистки добавлены только
7 ресурсов с уникальными seed-категориями:

- `bayonet-gamma-doppler.json` — Emerald, Green Galaxy и Max Green;
- `falchion-doppler.json` — Max Blue P3 и другие BTA P3;
- `huntsman-gamma-doppler.json` — Max Green P2;
- `m9-bayonet-gamma-doppler.json` — Fake/Max Lime Tip P4;
- `skeleton-doppler.json` — Max Blue/Pink и Pink Galaxy категории;
- `survival-doppler.json` — Fake Max Blue, Max Pink и другие BTA;
- `ssg08-acid-fade.json` — Full Fade и Black Pearls.

Шаблонные категории с повторными tier-листами не переносились. Гайды без фактических
seed-таблиц не превращались в выдуманные категории. Остальные непокрытые гайды либо
не относятся к pattern badge, либо требуют отдельного получения/проверки источника.

## Проверка

- JSON: обязательные поля, непустые списки, уникальность внутри каждого списка, seed 0..1000.
- `./gradlew test --rerun-tasks` — 30 tests passed.
- Добавлены проверки новых seed-категорий и границ Fade 99.9/96.0/90.0.
- `bootJar` пересобран после JSON, затем локальный wiki rollout успешен.
- Live API: SSG 08 Acid Fade seed 576 → `full_fade` HTTP 200;
  Bayonet Gamma Doppler seed 273 + Emerald → `emerald` HTTP 200.
