package com.skinex.pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.config.ObjectMapperConfig;
import com.skinex.pattern.service.PatternRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatternRegistryTest {

    @Test
    void loadsAphrodite() {
        ObjectMapper m = new ObjectMapperConfig().objectMapper();
        PatternRegistry reg = new PatternRegistry(m);
        int files = reg.loadAll();
        assertTrue(files >= 1, "should load at least 1 file");
        assertTrue(reg.listSkinsNormalized().contains("ak-47 | aphrodite"));
        // #904 — Pink Gem best
        var pi = reg.get("AK-47 | Aphrodite", 904);
        assertTrue(pi.isPresent());
        assertEquals("pink_gem", pi.get().category());
        assertTrue(pi.get().isBest());
        // #308 — Purple Gem best
        var purple = reg.get("ak-47 | aphrodite", 308);
        assertTrue(purple.isPresent());
        assertEquals("purple_gem", purple.get().category());
        // #820 — Top Pink Line best
        var line = reg.get("AK-47 | Aphrodite", 820);
        assertTrue(line.isPresent());
        assertEquals("top_pink_line", line.get().category());
        // unknown seed 9999 out of range? 999 is top_pink_line tier2
        var none = reg.get("AK-47 | Aphrodite", 2);
        assertTrue(none.isPresent());
        assertEquals("gold_gem", none.get().category());
        // skin without features
        assertFalse(reg.get("AWP | Dragon Lore", 123).isPresent());
        // case insensitive
        assertTrue(reg.get("AK-47 | APHRODITE", 904).isPresent());
    }

    @Test
    void totalSeedsReasonable() {
        ObjectMapper m = new ObjectMapperConfig().objectMapper();
        PatternRegistry reg = new PatternRegistry(m);
        reg.loadAll();
        // aphrodite alone ~990, now with 115 skins total >4000
        assertTrue(reg.totalIndexedSeeds() > 800, "should index many seeds");
        assertTrue(reg.totalIndexedSeeds() > 3000, "expanded catalog should be >3000");
        // also check case hardened
        var akBlue = reg.get("AK-47 | Case Hardened", 661);
        assertTrue(akBlue.isPresent(), "AK Case Hardened 661 should be indexed");
        assertEquals("blue_gem", akBlue.get().category());
    }
}
