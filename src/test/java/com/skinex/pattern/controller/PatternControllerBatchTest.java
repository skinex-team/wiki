package com.skinex.pattern.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.config.ObjectMapperConfig;
import com.skinex.pattern.service.FadePercentageService;
import com.skinex.pattern.service.PatternRegistry;
import com.skinex.pattern.service.PatternService;
import com.skinex.pattern.service.RedisPatternStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatternControllerBatchTest {

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ObjectMapper mapper = new ObjectMapperConfig().objectMapper();
        PatternRegistry reg = new PatternRegistry(mapper);
        reg.loadAll();
        PatternService svc = new PatternService(reg, new RedisPatternStore(null, mapper, "pattern", 0) {},
                new FadePercentageService(mapper));
        mvc = MockMvcBuilders.standaloneSetup(new PatternController(svc)).build();
    }

    private static String batchJson(String... elements) {
        return "[" + String.join(",", elements) + "]";
    }

    @Test
    void validElementReturnsPatternInfo() throws Exception {
        mvc.perform(post("/api/patterns/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("{\"skin\":\"AK-47 | Aphrodite\",\"seed\":904}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasFeatures").value(true))
                .andExpect(jsonPath("$[0].category").value("pink_gem"));
    }

    @Test
    void nullSkinElementSkippedWithoutBreakingBatch() throws Exception {
        mvc.perform(post("/api/patterns/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(
                                "{\"skin\":null,\"seed\":1}",
                                "{\"skin\":\"AK-47 | Aphrodite\",\"seed\":904}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasFeatures").value(false))
                .andExpect(jsonPath("$[1].hasFeatures").value(true))
                .andExpect(jsonPath("$[1].category").value("pink_gem"));
    }

    @Test
    void missingSeedDoesNotFallBackToRealSeedZero() throws Exception {
        // без seed раньше десериализовался в int 0 — а 0 может быть реальным сидом
        mvc.perform(post("/api/patterns/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("{\"skin\":\"AK-47 | Aphrodite\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasFeatures").value(false));
    }

    @Test
    void seedOutOfRangeSkipped() throws Exception {
        mvc.perform(post("/api/patterns/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(
                                "{\"skin\":\"AK-47 | Aphrodite\",\"seed\":-1}",
                                "{\"skin\":\"AK-47 | Aphrodite\",\"seed\":1001}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasFeatures").value(false))
                .andExpect(jsonPath("$[1].hasFeatures").value(false));
    }

    @Test
    void batchOverLimitReturns400() throws Exception {
        String big = "[" + String.join(",",
                Collections.nCopies(501, "{\"skin\":\"AK-47 | Aphrodite\",\"seed\":904}")) + "]";
        mvc.perform(post("/api/patterns/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(big))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyBodyReturns400() throws Exception {
        mvc.perform(post("/api/patterns/batch")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
