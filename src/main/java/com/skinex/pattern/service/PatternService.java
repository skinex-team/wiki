package com.skinex.pattern.service;

import com.skinex.pattern.model.PatternInfo;
import com.skinex.pattern.model.SkinPatterns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Фасад для REST/gRPC: сначала пробует Redis, затем in-memory реестр.
 * Тултип на фронте должен показываться ТОЛЬКО если есть PatternInfo (иначе скин без особенностей).
 */
@Service
public class PatternService {

    private static final Logger log = LoggerFactory.getLogger(PatternService.class);

    private final PatternRegistry registry;
    private final RedisPatternStore store;
    private final FadePercentageService fadeService;

    public PatternService(PatternRegistry registry, RedisPatternStore store, FadePercentageService fadeService) {
        this.registry = registry;
        this.store = store;
        this.fadeService = fadeService;
    }

    public Optional<PatternInfo> getInfo(String marketHashName, int seed) {
        return getInfo(marketHashName, seed, null);
    }

    /**
     * Тултип инфо для (skin, seed). phase — фаза предмета ("Phase 2", "P4", ...):
     * фазовые категории (суффикс _pN) фильтруются по ней, чтобы P2-предмет не получал
     * плашку Max Blue из P4. Сиды пересекаются между фазами (один сид — разное на разных фазах).
     * Без phase — старое поведение (первый кандидат по приоритету категорий).
     */
    public Optional<PatternInfo> getInfo(String marketHashName, int seed, String phase) {
        if (marketHashName == null) return Optional.empty();
        String norm = PatternRegistry.normalize(marketHashName);
        if (seed < 0 || seed > 1000) return Optional.empty();

        List<PatternInfo> candidates = registry.getCandidates(norm, seed);
        if (candidates.isEmpty()) return Optional.empty();
        PatternInfo base = pickByPhase(candidates, phase);
        if (base == null) return Optional.empty();

        // для Fade — точный процент + тир из ранкинга
        if (base.category() != null && base.category().contains("fade")) {
            var entryOpt = fadeService.getEntry(marketHashName, seed);
            if (entryOpt.isPresent()) {
                var e = entryOpt.get();
                String percStr = FadePercentageService.formatOneDecimal(e.percentage());
                int tier = FadePercentageService.tierForFade(e.percentage());
                int rank = e.ranking();
                boolean isBest = rank == 1;
                String newDisplay = percStr + " Fade Tier " + tier + " #" + rank;
                if (isBest) newDisplay = percStr + " Fade ★ Best #" + base.seed();
                base = new PatternInfo(
                        base.skin(), base.normalizedSkin(), base.seed(),
                        base.category(), base.categoryLabel(), base.categoryLabelRu(),
                        percStr, tier, rank, isBest, newDisplay, base.description()
                );
            }
        }
        return Optional.of(base);
    }

    private static final java.util.regex.Pattern PHASE_SUFFIX =
            java.util.regex.Pattern.compile("_p(\\d+)$");
    private static final java.util.regex.Pattern PHASE_DIGITS =
            java.util.regex.Pattern.compile("(\\d+)");

    private static Integer phaseSuffix(String category) {
        if (category == null) return null;
        var m = PHASE_SUFFIX.matcher(category);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static Integer parsePhase(String phase) {
        if (phase == null || phase.isBlank()) return null;
        var m = PHASE_DIGITS.matcher(phase);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Гем-категории допплеров: соответствуют фазам Ruby/Sapphire/Black Pearl/Emerald. */
    private static final String RUBY = "ruby";
    private static final String SAPPHIRE = "sapphire";
    private static final String BLACK_PEARL = "black_pearl";
    private static final String EMERALD = "emerald";
    private static final java.util.Set<String> GEM_CATEGORIES = java.util.Set.of(
            RUBY, SAPPHIRE, BLACK_PEARL, EMERALD);

    /** Фаза-гем ("Ruby" -> ruby, "Black Pearl" -> black_pearl); для обычных фаз — null. */
    private static String gemCategory(String phase) {
        if (phase == null) return null;
        String p = phase.trim().toLowerCase();
        return switch (p) {
            case "ruby" -> RUBY;
            case "sapphire" -> SAPPHIRE;
            case "black pearl" -> BLACK_PEARL;
            case "emerald" -> EMERALD;
            default -> null;
        };
    }

    /**
     * Выбор кандидата по фазе предмета:
     * - нумерованная фаза (Phase 1..4) — только категории _pN с совпадающим N; гемы не показываем;
     * - гем-фаза (Ruby/Sapphire/Black Pearl/Emerald) — только её гем-категория, _pN не показываем;
     * - без фазы — первый кандидат (приоритет категорий в файле).
     */
    private static PatternInfo pickByPhase(List<PatternInfo> candidates, String phase) {
        Integer pn = parsePhase(phase);
        String gem = gemCategory(phase);
        if (pn == null && gem == null) return candidates.get(0);
        PatternInfo generic = null;
        for (PatternInfo pi : candidates) {
            if (matchesPhase(pi, pn, gem)) return pi;
            if (generic == null && isGeneric(pi)) generic = pi;
        }
        return generic;
    }

    /** Кандидат подходит под фазу: нумерованная — только её _pN, гем — только его категория. */
    private static boolean matchesPhase(PatternInfo pi, Integer pn, String gem) {
        Integer s = phaseSuffix(pi.category());
        if (s != null) return pn != null && s.equals(pn);
        if (GEM_CATEGORIES.contains(pi.category())) return gem != null && gem.equals(pi.category());
        return false;
    }

    /** Категория без привязки к фазе — fallback, когда под фазу ничего не подошло. */
    private static boolean isGeneric(PatternInfo pi) {
        return phaseSuffix(pi.category()) == null && !GEM_CATEGORIES.contains(pi.category());
    }

    /** Для скинов без особенностей вернет empty — фронт НЕ показывает тултип */
    public boolean hasFeatures(String marketHashName) {
        if (marketHashName == null) return false;
        String norm = PatternRegistry.normalize(marketHashName);
        // проверка через реестр (быстрее чем Redis set)
        return registry.listSkinsNormalized().contains(norm);
    }

    public List<String> listSkins() {
        // возвращаем marketHashName в исходном регистре для удобства фронта
        return registry.allSkins().stream()
                .map(s -> s.marketHashName() != null ? s.marketHashName() : s.skin())
                .sorted()
                .toList();
    }

    public List<String> listSkinsNormalized() {
        return registry.listSkinsNormalized();
    }

    public Optional<SkinPatterns> getSkinPatterns(String marketHashName) {
        // пробуем Redis для полного файла? пока из реестра
        return registry.getSkin(marketHashName);
    }

    public int reload() {
        int files = registry.loadAll();
        int keys = store.fillAll(registry);
        log.info("PatternService reload: files={}, keys={}", files, keys);
        return keys;
    }

    public int totalSeeds() {
        return registry.totalIndexedSeeds();
    }
}
