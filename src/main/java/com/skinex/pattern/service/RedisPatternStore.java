package com.skinex.pattern.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.model.PatternInfo;
import com.skinex.pattern.model.SkinPatterns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Заливка и чтение Redis.
 * Ключи:
 *   pattern:info:{normalizedSkin}:{seed} -> JSON PatternInfo
 *   pattern:skin:{normalizedSkin}       -> JSON SkinPatterns (полный файл)
 *   pattern:skins                       -> SET / JSON список скинов с фичами
 *   pattern:meta:version                -> версия заливки (timestamp)
 *
 * Чтение тултипа: GET pattern:info:{skin}:{seed}. Если нет — fallback в PatternRegistry (in-memory).
 * SETTTL = 0 — бессрочно, но перезаливается при старте.
 */
@Component
public class RedisPatternStore {

    private static final Logger log = LoggerFactory.getLogger(RedisPatternStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String prefix;
    private final long ttlMs;

    public RedisPatternStore(StringRedisTemplate redis, ObjectMapper mapper,
                             @Value("${pattern.redis.prefix:pattern}") String prefix,
                             @Value("${pattern.redis.ttl-ms:0}") long ttlMs) {
        this.redis = redis;
        this.mapper = mapper;
        this.prefix = prefix;
        this.ttlMs = ttlMs;
    }

    public String keyInfo(String normalizedSkin, int seed) {
        return prefix + ":info:" + normalizedSkin + ":" + seed;
    }

    public String keySkin(String normalizedSkin) {
        return prefix + ":skin:" + normalizedSkin;
    }

    public String keySkins() {
        return prefix + ":skins";
    }

    public String keyMetaVersion() {
        return prefix + ":meta:version";
    }

    public void putPatternInfo(PatternInfo pi) {
        try {
            String key = keyInfo(pi.normalizedSkin(), pi.seed());
            String json = mapper.writeValueAsString(pi);
            if (ttlMs > 0) {
                redis.opsForValue().set(key, json, ttlMs, TimeUnit.MILLISECONDS);
            } else {
                redis.opsForValue().set(key, json);
            }
        } catch (Exception e) {
            log.warn("Redis put info failed {}:{} {}", pi.normalizedSkin(), pi.seed(), e.getMessage());
        }
    }

    public PatternInfo getPatternInfo(String normalizedSkin, int seed) {
        try {
            String json = redis.opsForValue().get(keyInfo(normalizedSkin, seed));
            if (json == null) return null;
            return mapper.readValue(json, PatternInfo.class);
        } catch (Exception e) {
            log.warn("Redis get info failed {}:{} {}", normalizedSkin, seed, e.getMessage());
            return null;
        }
    }

    public void putSkin(SkinPatterns sp) {
        try {
            String norm = PatternRegistry.normalize(sp.marketHashName() != null ? sp.marketHashName() : sp.skin());
            String key = keySkin(norm);
            String json = mapper.writeValueAsString(sp);
            if (ttlMs > 0) {
                redis.opsForValue().set(key, json, ttlMs, TimeUnit.MILLISECONDS);
            } else {
                redis.opsForValue().set(key, json);
            }
        } catch (Exception e) {
            log.warn("Redis put skin failed {}: {}", sp.skin(), e.getMessage());
        }
    }

    public void putSkinsSet(Collection<String> normalizedSkins) {
        try {
            String key = keySkins();
            // храним как JSON массив для простого GET, + как SET для SISMEMBER
            String json = mapper.writeValueAsString(normalizedSkins);
            if (ttlMs > 0) {
                redis.opsForValue().set(key, json, ttlMs, TimeUnit.MILLISECONDS);
            } else {
                redis.opsForValue().set(key, json);
            }
            // также SADD для быстрой проверки существования
            String setKey = prefix + ":skins:set";
            // очищаем старый set
            try { redis.delete(setKey); } catch (Exception ignored) {}
            if (!normalizedSkins.isEmpty()) {
                redis.opsForSet().add(setKey, normalizedSkins.toArray(new String[0]));
                if (ttlMs > 0) redis.expire(setKey, Duration.ofMillis(ttlMs));
            }
        } catch (Exception e) {
            log.warn("Redis put skins set failed: {}", e.getMessage());
        }
    }

    public void putVersion(String version) {
        try {
            if (ttlMs > 0) {
                redis.opsForValue().set(keyMetaVersion(), version, ttlMs, TimeUnit.MILLISECONDS);
            } else {
                redis.opsForValue().set(keyMetaVersion(), version);
            }
        } catch (Exception e) {
            log.warn("Redis put version failed: {}", e.getMessage());
        }
    }

    public Set<String> getSkinsSet() {
        try {
            String json = redis.opsForValue().get(keySkins());
            if (json == null) return Set.of();
            return Set.copyOf(mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(Set.class, String.class)));
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** Полная заливка из реестра; после неё подчищает ключи удалённых сидов/скинов. */
    public int fillAll(PatternRegistry registry) {
        int count = 0;
        Set<String> live = new HashSet<>();
        for (Map.Entry<String, Map<Integer, List<PatternInfo>>> e : registry.snapshotIndex().entrySet()) {
            for (List<PatternInfo> infos : e.getValue().values()) {
                // один ключ на (skin, seed) — кладём первого кандидата (приоритет категорий)
                if (!infos.isEmpty()) {
                    PatternInfo pi = infos.get(0);
                    putPatternInfo(pi);
                    live.add(keyInfo(pi.normalizedSkin(), pi.seed()));
                    count++;
                }
            }
        }
        for (SkinPatterns sp : registry.allSkins()) {
            putSkin(sp);
            live.add(keySkin(PatternRegistry.normalize(sp.marketHashName() != null ? sp.marketHashName() : sp.skin())));
        }
        putSkinsSet(registry.listSkinsNormalized());
        putVersion(String.valueOf(System.currentTimeMillis()));
        int removed = deleteStale(live);
        log.info("Redis fill done: {} PatternInfo keys + {} skins ({} stale keys removed)", count, registry.allSkins().size(), removed);
        return count;
    }

    /**
     * Удаляет ключи {prefix}:info:* / {prefix}:skin:*, которых нет среди live.
     * SCAN вместо KEYS — не блокирует Redis в проде; ошибки чистки не роняют заливку.
     * Чистка после заливки (а не до) — живые ключи не мигают в окне reload.
     */
    private int deleteStale(Set<String> live) {
        int removed = 0;
        for (String match : List.of(prefix + ":info:*", prefix + ":skin:*")) {
            try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(match).count(500).build())) {
                List<String> batch = new ArrayList<>(500);
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    if (live.contains(key)) continue;
                    batch.add(key);
                    if (batch.size() >= 500) {
                        redis.delete(batch);
                        removed += batch.size();
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    redis.delete(batch);
                    removed += batch.size();
                }
            } catch (Exception e) {
                log.warn("Redis stale cleanup failed for {}: {}", match, e.getMessage());
            }
        }
        return removed;
    }
}
