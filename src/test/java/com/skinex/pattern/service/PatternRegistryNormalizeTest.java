package com.skinex.pattern.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.config.ObjectMapperConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatternRegistryNormalizeTest {

    @Test
    void stripsStatTrakAndSouvenirMarkers() {
        // ★-ножи: маркеры после ★
        assertEquals("★ karambit | doppler", PatternRegistry.normalize("★ StatTrak™ Karambit | Doppler"));
        assertEquals("★ karambit | doppler", PatternRegistry.normalize("★ Souvenir Karambit | Doppler"));
        // обычные оружия: маркер без ★
        assertEquals("ak-47 | redline", PatternRegistry.normalize("StatTrak™ AK-47 | Redline"));
        assertEquals("ak-47 | redline", PatternRegistry.normalize("Souvenir AK-47 | Redline"));
    }

    @Test
    void plainNamesUnchanged() {
        assertEquals("ak-47 | redline", PatternRegistry.normalize("AK-47 | Redline"));
        assertEquals("★ karambit | doppler", PatternRegistry.normalize("★ Karambit | Doppler"));
        assertEquals("ak-47 | redline", PatternRegistry.normalize("  AK-47 | REDLINE "));
        assertEquals("", PatternRegistry.normalize(null));
    }

    @Test
    void statTrakNameFindsSamePatterns() {
        ObjectMapper mapper = new ObjectMapperConfig().objectMapper();
        PatternRegistry reg = new PatternRegistry(mapper);
        reg.loadAll();
        // karambit-doppler.json: 31 — первый сид fake_black_pearl_p1
        var plain = reg.get("★ Karambit | Doppler", 31);
        assertTrue(plain.isPresent());
        assertEquals(plain, reg.get("★ StatTrak™ Karambit | Doppler", 31));
        assertEquals(plain, reg.get("★ Souvenir Karambit | Doppler", 31));
        // маркер без ★: StatTrak-оружие находит тот же сид, что и обычное
        var ch = reg.get("AK-47 | Case Hardened", 661);
        assertTrue(ch.isPresent());
        assertEquals(ch, reg.get("StatTrak™ AK-47 | Case Hardened", 661));
    }
}
