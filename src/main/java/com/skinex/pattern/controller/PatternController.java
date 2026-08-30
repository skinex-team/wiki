package com.skinex.pattern.controller;

import com.skinex.pattern.model.PatternInfo;
import com.skinex.pattern.model.SkinPatterns;
import com.skinex.pattern.service.PatternService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/patterns")
public class PatternController {

    private final PatternService service;

    public PatternController(PatternService service) {
        this.service = service;
    }

    /** Список скинов с особенностями (для фронта — чтобы знать когда показывать тултип) */
    @GetMapping("/skins")
    public List<String> skins() {
        return service.listSkins();
    }

    @GetMapping("/skins/normalized")
    public List<String> skinsNormalized() {
        return service.listSkinsNormalized();
    }

    /** Полный тир-лист по скину (для отладки/админки) */
    @GetMapping("/skin/{*skinName}")
    public ResponseEntity<SkinPatterns> skin(@PathVariable("skinName") String skinName) {
        // Spring 6 wildcard: {*var} захватывает слеши, но нам нужен marketHashName = "AK-47 | Aphrodite"
        // Поэтому принимаем encoded path и декодируем
        String decoded = decode(skinName);
        return service.getSkinPatterns(decoded)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Тултип: ?skin=AK-47%20|%20Aphrodite&seed=904  */
    @GetMapping
    public ResponseEntity<?> get(@RequestParam("skin") String skin,
                                 @RequestParam("seed") Integer seed,
                                 @RequestParam(value = "float", required = false) Double floatValue) {
        if (skin == null || skin.isBlank() || seed == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "skin and seed required"));
        }
        var infoOpt = service.getInfo(skin, seed);
        if (infoOpt.isEmpty()) {
            // 204 — нет особенностей, фронт НЕ показывает тултип (важно не 404 путать с ошибкой)
            return ResponseEntity.noContent().build();
        }
        PatternInfo pi = infoOpt.get();
        // Дополнительно можно вернуть float совет: если float>0.25 — "глушится" цвет
        if (floatValue != null) {
            String floatHint = floatHint(floatValue);
            java.util.HashMap<String, Object> m = new java.util.HashMap<>();
            m.put("skin", pi.skin());
            m.put("seed", pi.seed());
            m.put("category", pi.category());
            m.put("categoryLabel", pi.categoryLabel());
            m.put("categoryLabelRu", pi.categoryLabelRu());
            if (pi.percentage() != null) m.put("percentage", pi.percentage());
            m.put("tier", pi.tier() != null ? pi.tier() : 1);
            m.put("rank", pi.rank());
            m.put("isBest", pi.isBest());
            m.put("displayName", pi.displayName());
            m.put("description", pi.description() != null ? pi.description() : "");
            m.put("floatValue", floatValue);
            m.put("floatHint", floatHint);
            m.put("hasFeatures", true);
            return ResponseEntity.ok(m);
        }
        return ResponseEntity.ok(pi);
    }

    /** Быстрая проверка — есть ли у скина особенности (для батч-проверок фронта) */
    @GetMapping("/has-features")
    public Map<String, Object> hasFeatures(@RequestParam("skin") String skin) {
        boolean has = service.hasFeatures(skin);
        return Map.of("skin", skin, "hasFeatures", has);
    }

    /** Батч: POST /api/patterns/batch  body: [{skin, seed}, ...] -> [{skin, seed, info|null}, ...] */
    @PostMapping("/batch")
    public List<Map<String, Object>> batch(@RequestBody List<BatchRequest> req) {
        return req.stream().map(r -> {
            var opt = service.getInfo(r.skin(), r.seed());
            if (opt.isEmpty()) {
                return Map.<String, Object>of("skin", r.skin(), "seed", r.seed(), "hasFeatures", false);
            }
            PatternInfo pi = opt.get();
            java.util.HashMap<String, Object> mm = new java.util.HashMap<>();
            mm.put("skin", pi.skin());
            mm.put("seed", pi.seed());
            mm.put("category", pi.category());
            mm.put("categoryLabel", pi.categoryLabel());
            mm.put("categoryLabelRu", pi.categoryLabelRu());
            if (pi.percentage() != null) mm.put("percentage", pi.percentage());
            mm.put("tier", pi.tier());
            mm.put("rank", pi.rank());
            mm.put("isBest", pi.isBest());
            mm.put("displayName", pi.displayName());
            mm.put("hasFeatures", true);
            return mm;
        }).toList();
    }

    public record BatchRequest(String skin, int seed) {}

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String floatHint(double f) {
        if (f <= 0.07) return "Factory New — цвет максимально яркий";
        if (f <= 0.15) return "Minimal Wear — ярко";
        if (f <= 0.38) return "Field-Tested — заметно темнеет, теряет насыщенность";
        if (f <= 0.45) return "Well-Worn — сильно темнеет";
        return "Battle-Scarred — почти черный, редкий паттерн глушится";
    }
}
