package com.skinex.pattern.controller;

import com.skinex.pattern.service.PatternService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/patterns")
public class AdminPatternController {

    private final PatternService service;
    private final String adminSteamId;

    public AdminPatternController(PatternService service,
                                  @Value("${app.admin.steam-id:}") String adminSteamId) {
        this.service = service;
        this.adminSteamId = adminSteamId;
    }

    private boolean isAdmin(String steamId) {
        return steamId != null && !adminSteamId.isBlank() && steamId.equals(adminSteamId);
    }

    // Заглушка: проверка по header X-SteamId (реально — через JwtAuthenticationFilter как в других сервисах)
    // Для локалки можно вызвать без проверки, если ADMIN_STEAM_ID пустой.

    @PostMapping("/reload")
    public ResponseEntity<?> reload(@RequestHeader(value = "X-SteamId", required = false) String steamId) {
        // если admin не настроен — разрешаем всем (локальная разработка)
        if (!adminSteamId.isBlank() && !isAdmin(steamId)) {
            return ResponseEntity.status(403).body(Map.of("error", "admin only"));
        }
        int keys = service.reload();
        return ResponseEntity.ok(Map.of("reloaded", true, "keys", keys, "skins", service.listSkins().size()));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "skins", service.listSkins(),
                "skinsNormalized", service.listSkinsNormalized(),
                "totalSeeds", service.totalSeeds()
        );
    }
}
