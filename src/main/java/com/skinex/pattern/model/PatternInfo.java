package com.skinex.pattern.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Ответ тултипа для конкретного seed.
 * Используется как в REST, так и для записи в Redis (JSON).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PatternInfo(
        String skin,
        String normalizedSkin,
        int seed,
        String category,
        String categoryLabel,
        String categoryLabelRu,
        String percentage,        // для Fade: "100%", "96-99%" и т.д.
        Integer tier,
        Integer rank,
        boolean isBest,
        String displayName,
        String description
) {}
