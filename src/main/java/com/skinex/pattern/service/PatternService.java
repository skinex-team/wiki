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
        if (marketHashName == null) return Optional.empty();
        String norm = PatternRegistry.normalize(marketHashName);
        if (seed < 0 || seed > 1000) return Optional.empty();

        PatternInfo base = null;
        PatternInfo fromRedis = store.getPatternInfo(norm, seed);
        if (fromRedis != null) base = fromRedis;
        else base = registry.get(marketHashName, seed).orElse(null);
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
