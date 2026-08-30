package com.skinex.pattern.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Точный процент Fade по сиду и оружию.
 * Источник: https://github.com/chescos/csgo-fade-percentage-calculator/generated/fade-percentages.json
 * Формат: [{weapon:"AWP", percentages:[{seed, percentage, ranking}]}]
 * Оружие в файле — без скина, напр. "Karambit", "Glock-18", "AWP".
 * Маппим marketHashName "★ Karambit | Fade" -> weapon "Karambit".
 */
@Component
public class FadePercentageService {

    private static final Logger log = LoggerFactory.getLogger(FadePercentageService.class);

    private final ObjectMapper mapper;
    // weaponLower -> seed -> {percentage, ranking}
    private final Map<String, Map<Integer, Entry>> table = new HashMap<>();

    public record Entry(double percentage, int ranking) {}

    public FadePercentageService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource("fade-percentages.json").getInputStream()) {
            JsonNode root = mapper.readTree(is);
            if (!root.isArray()) return;
            for (JsonNode weaponNode : root) {
                String weapon = weaponNode.path("weapon").asText("");
                if (weapon.isBlank()) continue;
                String key = weapon.toLowerCase();
                Map<Integer, Entry> perSeed = new HashMap<>(1024);
                JsonNode arr = weaponNode.path("percentages");
                if (arr.isArray()) {
                    for (JsonNode e : arr) {
                        int seed = e.path("seed").asInt(-1);
                        double perc = e.path("percentage").asDouble(-1);
                        int rank = e.path("ranking").asInt(0);
                        if (seed >= 0 && perc >= 0) perSeed.put(seed, new Entry(perc, rank));
                    }
                }
                table.put(key, perSeed);
            }
            log.info("FadePercentageService loaded {} weapons, total seeds {}", table.size(), table.values().stream().mapToInt(Map::size).sum());
        } catch (Exception e) {
            log.warn("Failed to load fade-percentages.json: {}", e.getMessage());
        }
    }

    public Optional<Double> getExactPercentage(String marketHashName, int seed) {
        return getEntry(marketHashName, seed).map(Entry::percentage);
    }

    public Optional<Entry> getEntry(String marketHashName, int seed) {
        if (marketHashName == null || seed < 0 || seed > 1000) return Optional.empty();
        String weapon = extractWeapon(marketHashName);
        if (weapon == null) return Optional.empty();
        Map<Integer, Entry> perSeed = table.get(weapon.toLowerCase());
        if (perSeed == null) return Optional.empty();
        Entry v = perSeed.get(seed);
        return v != null ? Optional.of(v) : Optional.empty();
    }

    public static int tierForFade(double perc) {
        if (perc >= 99.9) return 1; // 100% и 99.9% — Tier1
        if (perc >= 96.0) return 2; // 96-99%
        if (perc >= 90.0) return 3; // 90-95%
        return 4; // 80-89%
    }

    public static String extractWeapon(String marketHashName) {
        if (marketHashName == null) return null;
        String s = marketHashName.trim();
        // убрать StatTrak/Souvenir и звезду
        s = s.replaceFirst("^(StatTrak™ |Souvenir |★\\s*)", "").trim();
        // до " | "
        int idx = s.indexOf(" | ");
        if (idx > 0) s = s.substring(0, idx);
        s = s.trim();
        if (s.isEmpty()) return null;
        // нормализация для маппинга: "Glock-18" stays, "M4A1-S" etc
        return s;
    }

    public static String formatOneDecimal(double v) {
        // 99.76 -> "99.8%", 100.0 -> "100%"
        double rounded = Math.round(v * 10.0) / 10.0;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05) {
            return String.format("%d%%", (int) Math.round(rounded));
        }
        return String.format("%.1f%%", rounded);
    }
}
