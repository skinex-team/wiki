package com.skinex.pattern.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * DTO для десериализации patterns/*.json
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkinPatterns(
        String skin,
        String marketHashName,
        String normalizedName,
        String source,
        String author,
        String updated,
        List<Double> floatRange,
        String notes,
        Map<String, CategoryDef> categories
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryDef(
            String label,
            String labelRu,
            String description,
            String percentage,
            Integer tier,       // для категорий без сид-листов (doppler/gamma-doppler фазы): 1 = самая ценная
            Boolean isBest,     // для категорий без сид-листов: редчайшая разновидность (ruby/sapphire/emerald)
            String note,        // произвольная заметка к категории
            List<Integer> best,
            List<Integer> all,
            List<Integer> all_desc,
            List<Integer> tier0,
            List<Integer> tier1,
            List<Integer> tier2,
            List<Integer> tier3,
            List<Integer> tier4,
            List<Integer> tier5,
            List<Integer> tier6,
            List<Integer> tier7,
            List<Integer> tier8,
            List<Integer> tier9,
            List<Integer> tier10,
            List<Integer> excluded
    ) {}
}
