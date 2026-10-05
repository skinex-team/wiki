# wiki

Сервис редких паттернов скинов CS2 (бывший pattern-service). Заливает Redis данными из `patterns/*.json` (Steam-гайды) и отдает их через REST/gRPC для тултипов на карточках лотов.

## Источник данных
- `AK-47 | Aphrodite` — `steamcommunity.com/sharedfiles/filedetails/?id=3651356503` автор `korenevskiy` (110k просмотров, primary source для white.market/CS.MONEY/SkinBaron). Сверен с `white.market/blog/guides/ak-47-aphrodite-pattern-guide-every-rare-pattern-explained`.

## REST API
```
GET /api/patterns/skins                          -> ["AK-47 | Aphrodite"]
GET /api/patterns?skin=AK-47%20|%20Aphrodite&seed=904&float=0.02  -> PatternInfo | 204
GET /api/patterns/has-features?skin=...          -> {hasFeatures:bool}
POST /api/patterns/batch  [{skin, seed}]         -> [{skin, seed, category...}|{hasFeatures:false}]
GET /api/patterns/skin/{marketHashName}          -> SkinPatterns (полный тир-лист)
GET /api/admin/patterns/stats                    -> stats
POST /api/admin/patterns/reload  (X-SteamId: ADMIN_STEAM_ID) -> перезаливка Redis
```

`204 No Content` = у скина нет особенностей → фронт НЕ показывает тултип (требование).

## Redis схема
```
pattern:info:{normalizedSkin}:{seed} -> JSON PatternInfo
pattern:skin:{normalizedSkin}        -> JSON SkinPatterns
pattern:skins                        -> JSON ["ak-47 | aphrodite"]
pattern:skins:set                    -> SET для SISMEMBER
pattern:meta:version                 -> timestamp
```
`SPRING_DATA_REDIS_HOST=redis:6379` (k8s), локально `localhost:6379`. TTL=0 (бессрочно, перезаливается при старте). Fallback — in-memory реестр если Redis недоступен.

## gRPC
Прото `src/main/proto/pattern_service.proto` (порт 9091):
```
GetPatternInfo, HasFeatures, ListSkins, GetSkinPatterns
```
Для сервер-сервер интеграции (p2p-market, quick-deals, private-deals, p2p-trade) если нужно считать оверпрайс на бэке.

## Фронт интеграция
- `frontend/lib/pattern-api.ts` — fetch с кэшем + batch
- `frontend/components/common/pattern-badge.tsx` — `<PatternBadge marketHashName={name} paintSeed={seed} floatValue={float} />`
  Показывается только если `204` не вернулся. Цвет по категории, ★ для Best, Tier + rank, тултип с описанием и floatHint.
- Уже встроен в `app/market/item/[name]/lot-card.tsx:206` и `app/quick-trade/components/trade-card.tsx:103`
- Для остальных карточек (p2p-trade, private-deals, inventory) — аналогично:
  ```tsx
  import { PatternBadge } from "@/components/common/pattern-badge";
  <PatternBadge marketHashName={item.name} paintSeed={item.paintSeed} floatValue={item.floatValue} compact />
  ```
  Для гридов использовать `usePatternBatch` чтобы за 1 запрос проверить N лотов.

## Добавление нового скина
1. Создать `src/main/resources/patterns/<normalized>.json` по образцу `ak-47-aphrodite.json`
2. `POST /api/admin/patterns/reload` или рестарт пода
3. Проверить `GET /api/patterns/skins` и `GET /api/patterns?skin=...&seed=...`

## Деплой
- `skinex-ci/k8s/base/services/wiki.yaml` + `ingress.yaml` (`/api/patterns`, `/api/admin/patterns` → service `wiki:8091`)
- `skinex-ci/k8s/base/configmap-common-env.yaml` + `overlays/prod/configmap-common-env.env` добавляют `WIKI_GRPC=static://wiki:9091`
- `frontend/Dockerfile` добавляет `NEXT_PUBLIC_PATTERN_SERVICE_URL`
- Порты: REST 8091, gRPC 9091

## Build & Test
```
./gradlew test
./gradlew bootJar
docker build -t ghcr.io/skinex-team/wiki:latest .
```
