package com.skinex.pattern.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.model.PatternInfo;
import com.skinex.pattern.model.SkinPatterns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory реестр всех скинов с редкими паттернами.
 * Загружает patterns/*.json при старте, индексирует по seed.
 * Используется как источник правды для заливки в Redis и для fallback-чтения.
 */
@Component
public class PatternRegistry {

    private static final Logger log = LoggerFactory.getLogger(PatternRegistry.class);

    private final ObjectMapper mapper;

    // normalizedSkin -> SkinPatterns (исходный JSON)
    private final Map<String, SkinPatterns> skinsByNormalized = new ConcurrentHashMap<>();

    // normalizedSkin -> seed -> PatternInfo
    private final Map<String, Map<Integer, PatternInfo>> index = new ConcurrentHashMap<>();

    // список нормализованных имён скинов с особенностями (для /api/patterns/skins)
    private volatile List<String> skinList = List.of();

    public PatternRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        loadAll();
    }

    public synchronized int loadAll() {
        skinsByNormalized.clear();
        index.clear();
        int files = 0;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:patterns/*.json");
            for (Resource r : resources) {
                try (InputStream is = r.getInputStream()) {
                    SkinPatterns sp = mapper.readValue(is, SkinPatterns.class);
                    String norm = normalize(sp.marketHashName() != null ? sp.marketHashName() : sp.skin());
                    // переопределяем normalizedName на каноничный
                    skinsByNormalized.put(norm, sp);
                    buildIndex(norm, sp);
                    files++;
                    log.info("Loaded patterns for {} ({} categories) from {}", sp.skin(), sp.categories() != null ? sp.categories().size() : 0, r.getFilename());
                } catch (Exception e) {
                    log.error("Failed to load pattern file {}: {}", r.getFilename(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan patterns/*.json: {}", e.getMessage(), e);
        }
        skinList = List.copyOf(new TreeSet<>(skinsByNormalized.keySet()));
        // Также добавляем исходные marketHashName для удобства
        log.info("PatternRegistry loaded {} skins: {}", skinsByNormalized.size(), skinList);
        return files;
    }

    private void buildIndex(String norm, SkinPatterns sp) {
        Map<Integer, PatternInfo> bySeed = new HashMap<>(1024);
        if (sp.categories() == null) return;
        for (Map.Entry<String, SkinPatterns.CategoryDef> e : sp.categories().entrySet()) {
            String catKey = e.getKey(); // pink_gem etc
            SkinPatterns.CategoryDef cd = e.getValue();
            if (cd == null) continue;
            String label = cd.label() != null ? cd.label() : catKey;
            String labelRu = cd.labelRu() != null ? cd.labelRu() : label;
            String desc = cd.description();
            String percentage = cd.percentage();

            // best — отдельно помечаем isBest и индексируем отдельно (многие best не входят в tier-листы)
            Set<Integer> bestSet = cd.best() != null ? new HashSet<>(cd.best()) : Set.of();
            // best всегда Tier 1, rank по порядку списка best
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.best(), 1, 1);

            // Определяем все листы по тирам
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.all_desc(), 1, null);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.all(), 1, null);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier0(), 0, 0);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier1(), 1, 1);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier2(), 2, null);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier3(), 3, null);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier4(), 4, null);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier5(), 5, 5);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier6(), 6, 6);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier7(), 7, 7);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier8(), 8, 8);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier9(), 9, 9);
            indexCategory(bySeed, sp, norm, catKey, label, labelRu, percentage, desc, bestSet, cd.tier10(), 10, 10);
            // pink_gem all_desc уже разобран, но там нет tier-полей — считаем tier=1
            // pink_dust — без tier
            if ("pink_dust".equals(catKey) && cd.all() != null) {
                // уже покрыто
            }
        }
        // Помечаем excluded как unknown (не индексируем)
        index.put(norm, bySeed);
    }

    private void indexCategory(Map<Integer, PatternInfo> bySeed, SkinPatterns sp, String norm,
                               String catKey, String label, String labelRu, String percentage, String desc,
                               Set<Integer> bestSet, List<Integer> seeds,
                               int tier, Integer explicitTier) {
        if (seeds == null || seeds.isEmpty()) return;
        // Для all_desc нам нужен rank по порядку списка
        for (int i = 0; i < seeds.size(); i++) {
            int seed = seeds.get(i);
            if (bySeed.containsKey(seed)) continue; // первый (более приоритетный) wins — best/tier1 имеет приоритет
            boolean isBest = bestSet.contains(seed);
            int rank = i + 1;
            // Для tier1..4 rank внутри тира тоже по порядку
            Integer t = explicitTier != null ? explicitTier : (tier == 1 && seeds.size() > 0 && !catKey.equals("pink_gem") && !catKey.equals("pink_dust") ? tier : (catKey.equals("pink_gem") || catKey.equals("pink_dust") ? 1 : tier));
            // pink_gem all_desc содержит best в начале — rank уже правильный
            String displayName;
            if ("pink_dust".equals(catKey)) {
                displayName = label + " (common)";
            } else if (percentage != null && !percentage.isBlank() && catKey.contains("fade")) {
                if (isBest) displayName = percentage + " Fade ★ Best #" + seed;
                else displayName = percentage + " Fade Tier " + t + " #" + rank;
            } else if (percentage != null && !percentage.isBlank()) {
                if (isBest) displayName = label + " " + percentage + " ★ Best #" + seed;
                else displayName = label + " " + percentage + " Tier " + t + " #" + rank;
            } else if (isBest) {
                displayName = label + " ★ Best #" + seed;
            } else {
                displayName = label + " Tier " + t + " #" + rank;
            }
            PatternInfo pi = new PatternInfo(
                    sp.skin(),
                    norm,
                    seed,
                    catKey,
                    label,
                    labelRu,
                    percentage,
                    t,
                    rank,
                    isBest,
                    displayName,
                    desc
            );
            bySeed.put(seed, pi);
        }
    }

    public Optional<PatternInfo> get(String marketHashName, int seed) {
        if (marketHashName == null) return Optional.empty();
        String norm = normalize(marketHashName);
        Map<Integer, PatternInfo> m = index.get(norm);
        if (m == null) return Optional.empty();
        return Optional.ofNullable(m.get(seed));
    }

    public Optional<SkinPatterns> getSkin(String marketHashName) {
        if (marketHashName == null) return Optional.empty();
        return Optional.ofNullable(skinsByNormalized.get(normalize(marketHashName)));
    }

    public List<String> listSkinsNormalized() {
        return skinList;
    }

    public Collection<SkinPatterns> allSkins() {
        return skinsByNormalized.values();
    }

    public Map<String, Map<Integer, PatternInfo>> snapshotIndex() {
        return Collections.unmodifiableMap(index);
    }

    public static String normalize(String marketHashName) {
        if (marketHashName == null) return "";
        return marketHashName.trim().toLowerCase(Locale.ROOT);
    }

    public int totalIndexedSeeds() {
        return index.values().stream().mapToInt(Map::size).sum();
    }
}
