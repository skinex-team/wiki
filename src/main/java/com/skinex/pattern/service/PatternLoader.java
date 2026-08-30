package com.skinex.pattern.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * При старте заливает Redis данными из PatternRegistry.
 * Ошибки Redis не роняют приложение — тултип упадет в fallback на in-memory.
 */
@Component
public class PatternLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PatternLoader.class);

    private final PatternRegistry registry;
    private final RedisPatternStore store;

    public PatternLoader(PatternRegistry registry, RedisPatternStore store) {
        this.registry = registry;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int keys = store.fillAll(registry);
            log.info("PatternLoader: Redis filled with {} keys ({} skins)", keys, registry.allSkins().size());
        } catch (Exception e) {
            log.warn("PatternLoader: Redis fill failed (fallback to in-memory): {}", e.getMessage(), e);
        }
    }
}
