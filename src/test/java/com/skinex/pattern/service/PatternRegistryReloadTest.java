package com.skinex.pattern.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.config.ObjectMapperConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PatternRegistryReloadTest {

    private final ObjectMapper mapper = new ObjectMapperConfig().objectMapper();

    private static void writeSkin(Path dir, String filename, String skin, int... seeds) throws Exception {
        String json = "{\"skin\":\"" + skin + "\",\"marketHashName\":\"" + skin + "\","
                + "\"categories\":{\"blue_gem\":{\"label\":\"Blue Gem\",\"best\":" + Arrays.toString(seeds) + "}}}";
        Files.writeString(dir.resolve(filename), json);
    }

    @Test
    void reloadFullyReplacesPreviousState(@TempDir Path tmpA, @TempDir Path tmpB) throws Exception {
        writeSkin(tmpA, "alpha.json", "Test | Alpha", 1);
        writeSkin(tmpB, "beta.json", "Test | Beta", 2);
        PatternRegistry reg = new PatternRegistry(mapper);
        reg.loadAll("file:" + tmpA.toAbsolutePath() + "/*.json");
        assertTrue(reg.getSkin("Test | Alpha").isPresent());

        reg.loadAll("file:" + tmpB.toAbsolutePath() + "/*.json");
        assertTrue(reg.getSkin("Test | Alpha").isEmpty(), "старая заливка должна исчезнуть после reload");
        assertTrue(reg.get("Test | Alpha", 1).isEmpty());
        assertTrue(reg.getSkin("Test | Beta").isPresent());
        assertTrue(reg.get("Test | Beta", 2).isPresent());
        assertEquals(List.of("test | beta"), reg.listSkinsNormalized());
    }

    @Test
    void readersNeverSeePartialStateDuringReload(@TempDir Path tmp) throws Exception {
        writeSkin(tmp, "alpha.json", "Test | Alpha", 1, 2, 3);
        writeSkin(tmp, "beta.json", "Test | Beta", 4, 5, 6);
        PatternRegistry reg = new PatternRegistry(mapper);
        String loc = "file:" + tmp.toAbsolutePath() + "/*.json";
        reg.loadAll(loc);

        AtomicBoolean inconsistent = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !inconsistent.get()) {
                List<String> skins = reg.listSkinsNormalized();
                for (String s : skins) {
                    // инвариант: скин из списка всегда имеет и JSON, и индекс сидов
                    if (reg.getSkin(s).isEmpty()) {
                        inconsistent.set(true);
                        return;
                    }
                }
                if (skins.contains("test | alpha") && reg.getCandidates("test | alpha", 1).isEmpty()) {
                    inconsistent.set(true);
                    return;
                }
            }
        });
        reader.setDaemon(true);
        reader.start();
        for (int i = 0; i < 30 && !inconsistent.get(); i++) {
            reg.loadAll(loc);
        }
        reader.interrupt();
        reader.join(5000);
        assertFalse(inconsistent.get(), "читатель увидел частично загруженное состояние во время reload");
    }
}
