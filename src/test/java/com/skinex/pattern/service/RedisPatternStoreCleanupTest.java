package com.skinex.pattern.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.config.ObjectMapperConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedisPatternStoreCleanupTest {

    private final ObjectMapper mapper = new ObjectMapperConfig().objectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void fillAllRemovesStaleKeysButKeepsLiveOnes(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("good.json"), """
                {"skin":"Test | Good","marketHashName":"Test | Good",
                 "categories":{"blue_gem":{"label":"Blue Gem","best":[1]}}}
                """);
        PatternRegistry reg = new PatternRegistry(mapper);
        reg.loadAll("file:" + tmp.toAbsolutePath() + "/*.json");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        // scan отдаёт один устаревший ключ и один живой (только что залитый)
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("pattern:info:old-skin:1", "pattern:info:test | good:1");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        RedisPatternStore store = new RedisPatternStore(redis, mapper, "pattern", 0);
        int written = store.fillAll(reg);
        assertEquals(1, written);

        ArgumentCaptor<Collection<String>> deleted = ArgumentCaptor.forClass(Collection.class);
        verify(redis, times(1)).delete(deleted.capture());
        List<String> removed = deleted.getValue().stream().toList();
        assertTrue(removed.contains("pattern:info:old-skin:1"), "устаревший ключ должен быть удалён");
        assertFalse(removed.contains("pattern:info:test | good:1"), "живой ключ удалять нельзя");
    }
}
