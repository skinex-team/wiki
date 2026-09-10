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

/**
 * In-memory реестр всех скинов с редкими паттернами.
 * Загружает patterns/*.json при старте, индексирует по seed.
 * Используется как источник правды для заливки в Redis и для fallback-чтения.
 */
@Component
public class PatternRegistry {

    private static final Logger log = LoggerFactory.getLogger(PatternRegistry.class);

    private final ObjectMapper mapper;

    /**
     * Всё состояние реестра одним immutable-объектом: reload строит новые структуры локально
     * и подменяет ссылку атомарно — читатели никогда не видят частично загруженный индекс.
     *
     * @param skinsByNormalized normalizedSkin -> SkinPatterns (исходный JSON)
     * @param index normalizedSkin -> seed -> [PatternInfo] по приоритету категорий.
     *              Один сид может быть в нескольких категориях (напр. один сид — Max Blue на P4 и
     *              Pink Galaxy на P2): get() отдаёт первую, getCandidates — все, выбор по фазе предмета.
     * @param skinList список нормализованных имён скинов с особенностями (для /api/patterns/skins)
     */
    private record RegistryState(Map<String, SkinPatterns> skinsByNormalized,
                                 Map<String, Map<Integer, List<PatternInfo>>> index,
                                 List<String> skinList) {}

    private volatile RegistryState state = new RegistryState(Map.of(), Map.of(), List.of());

    public PatternRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        loadAll();
    }

    public synchronized int loadAll() {
        return loadAll("classpath:patterns/*.json");
    }

    /**
     * Перезагружает все файлы по паттерну (в тестах — file: на временную папку).
     * Битый/невалидный файл не роняет загрузку остальных: логируем WARNING и пропускаем.
     */
    synchronized int loadAll(String locationPattern) {
        // Строим новое состояние локально и публикуем одной volatile-записью в конце:
        // в окне reload читатели продолжают видеть предыдущее состояние целиком.
        Map<String, SkinPatterns> newSkins = new HashMap<>();
        Map<String, Map<Integer, List<PatternInfo>>> newIndex = new HashMap<>();
        int files = 0;
        int skipped = 0;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(locationPattern);
            for (Resource r : resources) {
                String filename = r.getFilename();
                try (InputStream is = r.getInputStream()) {
                    SkinPatterns sp = mapper.readValue(is, SkinPatterns.class);
                    Validation v = validate(filename, sp);
                    v.problems().forEach(p -> log.warn("Pattern file {}: {}", filename, p));
                    if (v.fatal()) {
                        skipped++;
                        log.warn("Pattern file {} skipped (fatal validation problems)", filename);
                        continue;
                    }
                    String norm = normalize(sp.marketHashName() != null ? sp.marketHashName() : sp.skin());
                    // переопределяем normalizedName на каноничный
                    newSkins.put(norm, sp);
                    buildIndex(newIndex, norm, sp);
                    files++;
                    log.info("Loaded patterns for {} ({} categories) from {}", sp.skin(), sp.categories() != null ? sp.categories().size() : 0, filename);
                } catch (Exception e) {
                    skipped++;
                    log.error("Failed to load pattern file {}: {}", filename, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan {}: {}", locationPattern, e.getMessage(), e);
        }
        Map<String, Map<Integer, List<PatternInfo>>> immutableIndex = new HashMap<>();
        newIndex.forEach((k, v) -> immutableIndex.put(k, Collections.unmodifiableMap(v)));
        state = new RegistryState(
                Collections.unmodifiableMap(newSkins),
                Collections.unmodifiableMap(immutableIndex),
                List.copyOf(new TreeSet<>(newSkins.keySet())));
        // Также добавляем исходные marketHashName для удобства
        log.info("PatternRegistry loaded {} skins: {}", newSkins.size(), state.skinList());
        // Сводка покрытия: сколько файлов/скинов/сидов реально в индексе
        log.info("PatternRegistry load summary: files loaded={}, skins indexed={}, total seeds indexed={}, files skipped={}",
                files, newSkins.size(), totalIndexedSeeds(), skipped);
        return files;
    }

    /**
     * Результат валидации одного файла паттернов.
     * fatal=true — файл пропускаем целиком (нет marketHashName или categories).
     */
    record Validation(boolean fatal, List<String> problems) {}

    /**
     * Проверяет распарсенный файл, ничего не бросает — возвращает список проблем для WARNING-лога.
     * Дубликаты сидов только репортим: рантайм-контракт buildIndex — first write wins.
     */
    Validation validate(String filename, SkinPatterns sp) {
        List<String> problems = new ArrayList<>();
        if (sp.marketHashName() == null || sp.marketHashName().isBlank()) {
            problems.add("missing marketHashName");
            return new Validation(true, problems);
        }
        if (sp.categories() == null || sp.categories().isEmpty()) {
            problems.add("missing or empty categories");
            return new Validation(true, problems);
        }

        // Опечатки в ключах категорий: "tire_" вместо "tier_" и двойное подчёркивание
        List<String> tireKeys = sp.categories().keySet().stream().filter(k -> k.contains("tire_")).toList();
        if (!tireKeys.isEmpty()) {
            problems.add("category keys contain 'tire_' typo (should be 'tier_'): " + tireKeys);
        }
        List<String> duKeys = sp.categories().keySet().stream().filter(k -> k.contains("__")).toList();
        if (!duKeys.isEmpty()) {
            problems.add("category keys contain double underscore: " + duKeys);
        }

        // gamma-doppler — это emerald + phase1..4; ruby/sapphire/black_pearl там из обычного doppler
        if (filename != null && filename.contains("gamma-doppler")) {
            List<String> wrong = List.of("ruby", "sapphire", "black_pearl", "fake_black_pearl_p1").stream()
                    .filter(sp.categories()::containsKey).toList();
            if (!wrong.isEmpty()) {
                problems.add("gamma-doppler file contains doppler gem categories instead of emerald: " + wrong);
            }
        }

        // Сиды: диапазон 0..1000 и дубликаты между листами внутри категории
        for (Map.Entry<String, SkinPatterns.CategoryDef> e : sp.categories().entrySet()) {
            String catKey = e.getKey();
            SkinPatterns.CategoryDef cd = e.getValue();
            if (cd == null) continue;
            Map<Integer, List<String>> occurrences = new LinkedHashMap<>();
            List<String> outOfRange = new ArrayList<>();
            for (Map.Entry<String, List<Integer>> list : seedLists(cd).entrySet()) {
                for (Integer seed : list.getValue()) {
                    if (seed == null) continue;
                    occurrences.computeIfAbsent(seed, k -> new ArrayList<>()).add(list.getKey());
                    if (seed < 0 || seed > 1000) {
                        outOfRange.add(seed + " in " + list.getKey());
                    }
                }
            }
            List<String> dups = occurrences.entrySet().stream()
                    .filter(en -> en.getValue().size() > 1)
                    .map(en -> en.getKey() + " in " + en.getValue())
                    .toList();
            if (!dups.isEmpty()) {
                problems.add("duplicate seeds across lists in category '" + catKey + "': " + dups);
            }
            if (!outOfRange.isEmpty()) {
                problems.add("seeds out of range 0..1000 in category '" + catKey + "': " + outOfRange);
            }
        }
        return new Validation(false, problems);
    }

    /** Все сид-листы категории (best/all/all_desc/tier0..tier10/excluded), без null. */
    static Map<String, List<Integer>> seedLists(SkinPatterns.CategoryDef cd) {
        Map<String, List<Integer>> lists = new LinkedHashMap<>();
        lists.put("best", cd.best());
        lists.put("all", cd.all());
        lists.put("all_desc", cd.all_desc());
        lists.put("tier0", cd.tier0());
        lists.put("tier1", cd.tier1());
        lists.put("tier2", cd.tier2());
        lists.put("tier3", cd.tier3());
        lists.put("tier4", cd.tier4());
        lists.put("tier5", cd.tier5());
        lists.put("tier6", cd.tier6());
        lists.put("tier7", cd.tier7());
        lists.put("tier8", cd.tier8());
        lists.put("tier9", cd.tier9());
        lists.put("tier10", cd.tier10());
        lists.put("excluded", cd.excluded());
        lists.values().removeIf(Objects::isNull);
        return lists;
    }

    private void buildIndex(Map<String, Map<Integer, List<PatternInfo>>> target, String norm, SkinPatterns sp) {
        Map<Integer, List<PatternInfo>> bySeed = new HashMap<>(1024);
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
        target.put(norm, bySeed);
    }

    private void indexCategory(Map<Integer, List<PatternInfo>> bySeed, SkinPatterns sp, String norm,
                               String catKey, String label, String labelRu, String percentage, String desc,
                               Set<Integer> bestSet, List<Integer> seeds,
                               int tier, Integer explicitTier) {
        if (seeds == null || seeds.isEmpty()) return;
        // Для all_desc нам нужен rank по порядку списка
        for (int i = 0; i < seeds.size(); i++) {
            int seed = seeds.get(i);
            // сид может входить в несколько категорий (пересечения фаз) — копим все,
            // порядок категорий в файле = приоритет; get() берёт первую
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
            bySeed.computeIfAbsent(seed, k -> new ArrayList<>()).add(pi);
        }
    }

    public Optional<PatternInfo> get(String marketHashName, int seed) {
        if (marketHashName == null) return Optional.empty();
        String norm = normalize(marketHashName);
        Map<Integer, List<PatternInfo>> m = state.index().get(norm);
        if (m == null) return Optional.empty();
        List<PatternInfo> l = m.get(seed);
        return l == null || l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    /** Все категории для (skin, seed) по приоритету — для выбора по фазе предмета. */
    public List<PatternInfo> getCandidates(String marketHashName, int seed) {
        if (marketHashName == null) return List.of();
        Map<Integer, List<PatternInfo>> m = state.index().get(normalize(marketHashName));
        if (m == null) return List.of();
        List<PatternInfo> l = m.get(seed);
        return l == null ? List.of() : Collections.unmodifiableList(l);
    }

    public Optional<SkinPatterns> getSkin(String marketHashName) {
        if (marketHashName == null) return Optional.empty();
        return Optional.ofNullable(state.skinsByNormalized().get(normalize(marketHashName)));
    }

    public List<String> listSkinsNormalized() {
        return state.skinList();
    }

    public Collection<SkinPatterns> allSkins() {
        return state.skinsByNormalized().values();
    }

    /** Текущий индекс (уже immutable, копия не нужна) — для заливки в Redis. */
    public Map<String, Map<Integer, List<PatternInfo>>> snapshotIndex() {
        return state.index();
    }

    /**
     * Нормализация к ключу файлов паттернов: trim + lowercase, плюс срез Steam-маркеров
     * качества — в файлах паттернов их нет:
     * "★ StatTrak™ Karambit | Doppler" → "★ karambit | doppler",
     * "★ Souvenir Karambit | Doppler" → "★ karambit | doppler",
     * "StatTrak™ AK-47 | Redline" → "ak-47 | redline" (маркер без ★ тоже срезаем).
     */
    public static String normalize(String marketHashName) {
        if (marketHashName == null) return "";
        String s = marketHashName.trim().toLowerCase(Locale.ROOT);
        s = s.replace("stattrak™", "").replace("souvenir", "");
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    public int totalIndexedSeeds() {
        return state.index().values().stream().mapToInt(Map::size).sum();
    }
}
