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
        // после чистки шаблонных копипаст-листов gem-данные остались не у всех ножей:
        // Stiletto — ruby, m9 — black_pearl, glock/karambit gamma — emerald
        SkinPatterns sp = reg.getSkin("★ Stiletto Knife | Doppler").orElseThrow();
        Map<String, SkinPatterns.CategoryDef> cats = sp.categories();
        SkinPatterns.CategoryDef ruby = cats.get("ruby");
        assertNotNull(ruby);
        assertFalse(ruby.tier1().isEmpty(), "ruby tier1 пуст");
        assertFalse(ruby.tier2().isEmpty(), "ruby tier2 пуст");

        SkinPatterns m9 = reg.getSkin("★ M9 Bayonet | Doppler").orElseThrow();
        SkinPatterns.CategoryDef bp = m9.categories().get("black_pearl");
        assertNotNull(bp, "m9 black_pearl удалён вместе с шаблонами — нужен источник данных");
        assertFalse(bp.tier1().isEmpty());

        // фазовые особенности: fake black pearl P1 с тир-листами
        SkinPatterns bayonet = reg.getSkin("★ Bayonet | Doppler").orElseThrow();
        assertNotNull(bayonet.categories().get("fake_black_pearl_p1"));
        assertFalse(bayonet.categories().get("fake_black_pearl_p1").tier1().isEmpty());

        // /api/patterns/skin/{name} сериализует SkinPatterns напрямую — поля должны быть в JSON
        String json = mapper.writeValueAsString(ruby);
        assertTrue(json.contains("\"tier1\""), json);
    }

    @Test
    void phaseFilterSeparatesWaveCategories() {
        PatternRegistry reg = loadReal();
        // Stiletto Doppler: после чистки осталась только gem-категория ruby; 93 — её сид
        assertTrue(reg.getCandidates("★ Stiletto Knife | Doppler", 93).size() >= 1);

        PatternService svc = new PatternService(reg, new RedisPatternStore(
                null, mapper, "pattern", 0) {}, new FadePercentageService(mapper));
        // без фазы — приоритет gem-категории
        assertEquals("ruby", svc.getInfo("★ Stiletto Knife | Doppler", 93).orElseThrow().category());
        // фаза Ruby — показываем её gem
        assertEquals("ruby", svc.getInfo("★ Stiletto Knife | Doppler", 93, "Ruby").orElseThrow().category());
        // нумерованная фаза — gem не показываем (P2-предмет не может быть рубином)
        assertTrue(svc.getInfo("★ Stiletto Knife | Doppler", 93, "Phase 2").isEmpty());
        // чужая gem-фаза — не показываем
        assertTrue(svc.getInfo("★ Stiletto Knife | Doppler", 93, "Sapphire").isEmpty());

        // регрессия: Black Pearl-лот стилета (сид 183) раньше получал плашку Max Blue —
        // tip-категории оказались шаблонными и удалены, BP у стилета тоже копипаст → пусто
        assertTrue(svc.getInfo("★ Stiletto Knife | Doppler", 183, "Black Pearl").isEmpty());
        assertTrue(svc.getInfo("★ Stiletto Knife | Doppler", 183).isEmpty());

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

        // gamma-doppler: после чистки шаблонов остались только файлы с уникальными данными;
        // doppler-gem категорий в них быть не должно (guard-проверка ни на одном не срабатывает)
        Resource[] gamma = new PathMatchingResourcePatternResolver().getResources("classpath:patterns/*gamma-doppler.json");
        assertTrue(gamma.length >= 5, "ожидаем >=5 gamma-doppler файлов, найдено " + gamma.length);
        for (Resource r : gamma) {
            String name = r.getFilename();
            assertNotNull(name);
            SkinPatterns gd;
            try (InputStream is = r.getInputStream()) {
                gd = mapper.readValue(is, SkinPatterns.class);
            }
            Map<String, SkinPatterns.CategoryDef> cats = gd.categories();
            boolean hasSeeds = cats.values().stream()
                    .anyMatch(cd -> PatternRegistry.seedLists(cd).values().stream().anyMatch(l -> !l.isEmpty()));
            assertTrue(hasSeeds, name + ": gamma-файл без сид-листов");
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
