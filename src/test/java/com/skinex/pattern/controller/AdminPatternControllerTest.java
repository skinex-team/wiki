package com.skinex.pattern.controller;

import com.skinex.pattern.auth.JwtAuthenticationFilter;
import com.skinex.pattern.auth.JwtUtil;
import com.skinex.pattern.service.PatternService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPatternControllerTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32";
    private static final String ADMIN = "76561199027967594";
    private static final String NOT_ADMIN = "76561198000000000";

    private PatternService service;
    private JwtUtil jwtUtil;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(PatternService.class);
        when(service.reload()).thenReturn(3);
        when(service.listSkins()).thenReturn(List.of("AK-47 | Aphrodite"));
        when(service.listSkinsNormalized()).thenReturn(List.of("ak-47 | aphrodite"));
        when(service.totalSeeds()).thenReturn(42);
        jwtUtil = jwtUtilWithSecret(SECRET);
        mvc = mvcWith(jwtUtil, ADMIN);
    }

    private static JwtUtil jwtUtilWithSecret(String secret) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        jwtUtil.init();
        return jwtUtil;
    }

    private MockMvc mvcWith(JwtUtil jwt, String adminSteamId) {
        return MockMvcBuilders
                .standaloneSetup(new AdminPatternController(service, adminSteamId))
                .addFilters(new JwtAuthenticationFilter(jwt))
                .build();
    }

    private static String token(String secret, String steamId) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().claim("steamId", steamId).signWith(key).compact();
    }

    @Test
    void reloadWithoutTokenReturns401() throws Exception {
        mvc.perform(post("/api/admin/patterns/reload"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).reload();
    }

    @Test
    void reloadWithSpoofedSteamIdHeaderReturns401() throws Exception {
        // регрессия: X-SteamId больше не является auth — без валидного JWT доступ запрещён
        mvc.perform(post("/api/admin/patterns/reload").header("X-SteamId", ADMIN))
                .andExpect(status().isUnauthorized());
        verify(service, never()).reload();
    }

    @Test
    void reloadWithNonAdminTokenReturns403() throws Exception {
        mvc.perform(post("/api/admin/patterns/reload")
                        .header("Authorization", "Bearer " + token(SECRET, NOT_ADMIN)))
                .andExpect(status().isForbidden());
        verify(service, never()).reload();
    }

    @Test
    void reloadWithAdminTokenReturns200() throws Exception {
        mvc.perform(post("/api/admin/patterns/reload")
                        .header("Authorization", "Bearer " + token(SECRET, ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reloaded").value(true))
                .andExpect(jsonPath("$.keys").value(3));
        verify(service).reload();
    }

    @Test
    void statsRequiresAdminToo() throws Exception {
        mvc.perform(get("/api/admin/patterns/stats"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/patterns/stats")
                        .header("Authorization", "Bearer " + token(SECRET, NOT_ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/patterns/stats")
                        .header("Authorization", "Bearer " + token(SECRET, ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeeds").value(42));
    }

    @Test
    void forgedTokenWithWrongSecretReturns401() throws Exception {
        mvc.perform(post("/api/admin/patterns/reload")
                        .header("Authorization", "Bearer " + token("wrong-secret-wrong-secret-wrong-32", ADMIN)))
                .andExpect(status().isUnauthorized());
        verify(service, never()).reload();
    }

    @Test
    void blankSecretRejectsAllTokens() throws Exception {
        JwtUtil noKey = jwtUtilWithSecret("");
        assertTrue(noKey.validateToken(token(SECRET, ADMIN)).isEmpty(),
                "без NEXTAUTH_SECRET все токены должны отклоняться");
        mvcWith(noKey, ADMIN).perform(post("/api/admin/patterns/reload")
                        .header("Authorization", "Bearer " + token(SECRET, ADMIN)))
                .andExpect(status().isUnauthorized());
        verify(service, never()).reload();
    }

    @Test
    void blankAdminSteamIdDeniesEveryone() throws Exception {
        // регрессия: пустой app.admin.steam-id раньше открывал reload всем анонимам
        mvcWith(jwtUtil, "").perform(post("/api/admin/patterns/reload")
                        .header("Authorization", "Bearer " + token(SECRET, ADMIN)))
                .andExpect(status().isForbidden());
        verify(service, never()).reload();
    }
}
