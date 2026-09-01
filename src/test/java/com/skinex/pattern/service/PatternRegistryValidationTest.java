package com.skinex.pattern.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.config.ObjectMapperConfig;
import com.skinex.pattern.model.SkinPatterns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PatternRegistryValidationTest {

    private final ObjectMapper mapper = new ObjectMapperConfig().objectMapper();

    private PatternRegistry loadReal() {
        PatternRegistry reg = new PatternRegistry(mapper);
        reg.loadAll();
        return reg;
    }

    private SkinPatterns readClasspath(String path) throws Exception {
        try (InputStream is = new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + path).getInputStream()) {
            return mapper.readValue(is, SkinPatterns.class);
        }
    }

    @Test
    void dopplerGemCategoriesHaveSeedTiers() throws Exception {
        PatternRegistry reg = loadReal();
        SkinPatterns sp = reg.getSkin("★ Bayonet | Doppler").orElseThrow();
        Map<String, SkinPatterns.CategoryDef> cats = sp.categories();
        // gem-категории: реальные сид-листы из гайдов korenevskiy (Rank I/II/III → tier1..3)
        for (String gem : List.of("ruby", "sapphire", "black_pearl")) {
            SkinPatterns.CategoryDef c = cats.get(gem);
            assertNotNull(c, gem);
            assertFalse(c.tier1().isEmpty(), gem + " tier1 пуст");
            assertFalse(c.tier2().isEmpty(), gem + " tier2 пуст");
        }
        // фазовые особенности: fake black pearl P1 с тир-листами
        assertNotNull(cats.get("fake_black_pearl_p1"));
        assertFalse(cats.get("fake_black_pearl_p1").tier1().isEmpty());

        // /api/patterns/skin/{name} сериализует SkinPatterns напрямую — поля должны быть в JSON
        String json = mapper.writeValueAsString(cats.get("ruby"));
        assertTrue(json.contains("\"tier1\""), json);
    }

    @Test
    void phaseFilterSeparatesWaveCategories() {
        PatternRegistry reg = loadReal();
        // сид 610 на Karambit Doppler: ruby (tier1) и фазовые категории
        assertTrue(reg.getCandidates("★ Karambit | Doppler", 610).size() >= 1);

        PatternService svc = new PatternService(reg, new RedisPatternStore(
                null, mapper, "pattern", 0) {}, new FadePercentageService(mapper));
        // без фазы — приоритет gem-категории
        assertEquals("ruby", svc.getInfo("★ Karambit | Doppler", 610).orElseThrow().category());
        // фаза P2: gem остаётся (нефазовая категория)
        assertEquals("ruby", svc.getInfo("★ Karambit | Doppler", 610, "Phase 2").orElseThrow().category());
        // фазовый сид: на P2 fake-BP-метка не протекает с другой фазы
        var anyPhase = svc.getInfo("★ Bayonet | Doppler", 44, "Phase 2");
        if (anyPhase.isPresent()) {
            String cat = anyPhase.get().category();
            assertFalse(cat.endsWith("_p1"), "P2-предмет получил плашку P1: " + cat);
        }
        // phase=Phase 1 → фазовая категория P1
        assertEquals("fake_black_pearl_p1",
                svc.getInfo("★ Bayonet | Doppler", 44, "Phase 1").orElseThrow().category());
    }

    @Test
    void malformedFileSkippedOthersLoad(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("good.json"), """
                {"skin":"Test | Good","marketHashName":"Test | Good",
                 "categories":{"blue_gem":{"label":"Blue Gem","best":[1],"tier1":[2,3]}}}
                """);
        Files.writeString(tmp.resolve("bad.json"), """
                {"skin":"Test | Bad","categories":{"x":{"label":"X","tier1":[1]}}}
                """);
        PatternRegistry reg = new PatternRegistry(mapper);
        int files = reg.loadAll("file:" + tmp.toAbsolutePath() + "/*.json");
        assertEquals(1, files, "должен загрузиться только good.json");
        assertTrue(reg.getSkin("Test | Good").isPresent());
        assertTrue(reg.getSkin("Test | Bad").isEmpty(), "файл без marketHashName пропускается");
        assertTrue(reg.listSkinsNormalized().contains("test | good"));

        // validate() помечает отсутствие marketHashName как fatal
        SkinPatterns bad;
        try (InputStream is = Files.newInputStream(tmp.resolve("bad.json"))) {
            bad = mapper.readValue(is, SkinPatterns.class);
        }
        PatternRegistry.Validation v = reg.validate("bad.json", bad);
        assertTrue(v.fatal());
        assertTrue(v.problems().stream().anyMatch(p -> p.contains("marketHashName")), v.problems().toString());
    }

    @Test
    void firstWriteWinsBestBeatsTier() {
        PatternRegistry reg = loadReal();
        // bayonet-doppler fake_black_pearl_p1: 44 — первый сид tier1 (гайд korenevskiy)
        var pi = reg.get("★ Bayonet | Doppler", 44).orElseThrow();
        assertEquals("fake_black_pearl_p1", pi.category());
        assertEquals(1, pi.rank());
        assertEquals(1, pi.tier());

        // AK CH 661 — в best; best индексируется первым => isBest, tier 1
        var ak = reg.get("AK-47 | Case Hardened", 661).orElseThrow();
        assertTrue(ak.isBest());
        assertEquals(1, ak.tier());
    }

    @Test
    void validationReportsDuplicatesAndKnownDataIssues() throws Exception {
        PatternRegistry reg = new PatternRegistry(mapper);

        // shipped-файлы с дубликатами между листами — репортим, а не падаем (first write wins)
        SkinPatterns doppler = readClasspath("patterns/bayonet-doppler.json");
        PatternRegistry.Validation v = reg.validate("bayonet-doppler.json", doppler);
        assertFalse(v.fatal());

        // m9 marble fade: опечатка tire_ исправлена в данных — validate её больше не находит
        SkinPatterns m9 = readClasspath("patterns/m9-bayonet-marble-fade.json");
        PatternRegistry.Validation vm9 = reg.validate("m9-bayonet-marble-fade.json", m9);
        assertTrue(vm9.problems().stream().noneMatch(p -> p.contains("tire_")), vm9.problems().toString());

        // gamma-doppler: данные починены — везде emerald и нет doppler-gem категорий,
        // поэтому guard-проверка gamma-файлов больше не срабатывает ни на одном shipped-файле
        Resource[] gamma = new PathMatchingResourcePatternResolver().getResources("classpath:patterns/*gamma-doppler.json");
        assertTrue(gamma.length >= 11, "ожидаем >=11 gamma-doppler файлов, найдено " + gamma.length);
        for (Resource r : gamma) {
            String name = r.getFilename();
            assertNotNull(name);
            SkinPatterns gd;
            try (InputStream is = r.getInputStream()) {
                gd = mapper.readValue(is, SkinPatterns.class);
            }
            Map<String, SkinPatterns.CategoryDef> cats = gd.categories();
            assertTrue(cats.containsKey("emerald"), name + ": нет категории emerald");
            for (String wrong : List.of("ruby", "sapphire", "black_pearl", "fake_black_pearl_p1")) {
                assertFalse(cats.containsKey(wrong), name + ": doppler-gem категория " + wrong + " в gamma-файле");
            }
            PatternRegistry.Validation vgd = reg.validate(name, gd);
            assertTrue(vgd.problems().stream().noneMatch(p -> p.contains("instead of emerald")),
                    name + ": " + vgd.problems());
        }
    }

    @Test
    void coverageInvariantsAcrossShippedFiles() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:patterns/*.json");
        assertTrue(resources.length > 100, "ожидаем >100 файлов паттернов, найдено " + resources.length);
        int fadeChecked = 0;
        int dopplerChecked = 0;
        for (Resource r : resources) {
            String name = r.getFilename();
            assertNotNull(name);
            SkinPatterns sp;
            try (InputStream is = r.getInputStream()) {
                sp = mapper.readValue(is, SkinPatterns.class);
            }
            Map<String, SkinPatterns.CategoryDef> cats = sp.categories();
            assertNotNull(cats, name + ": нет categories");
            if (name.contains("-fade")) {
                fadeChecked++;
                boolean hasTierLists = cats.values().stream()
                        .anyMatch(cd -> PatternRegistry.seedLists(cd).values().stream().anyMatch(l -> !l.isEmpty()));
                boolean hasFadeCategory = cats.keySet().stream().anyMatch(k -> k.contains("fade"));
                assertTrue(hasTierLists || hasFadeCategory,
                        name + ": fade-файл без тир-листов и без fade-категории");
            }
            if (name.contains("-doppler")) {
                dopplerChecked++;
                // каждый doppler/gamma-doppler файл должен нести хотя бы одну категорию с сидами
                boolean hasSeeds = cats.values().stream()
                        .anyMatch(cd -> PatternRegistry.seedLists(cd).values().stream().anyMatch(l -> !l.isEmpty()));
                assertTrue(hasSeeds, name + ": doppler-файл без сид-листов");
            }
        }
        assertTrue(fadeChecked > 10, "fadeChecked=" + fadeChecked);
        assertTrue(dopplerChecked > 10, "dopplerChecked=" + dopplerChecked);
    }
}
