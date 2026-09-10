package com.skinex.pattern.controller;

import com.skinex.pattern.auth.JwtAuthenticationFilter;
import com.skinex.pattern.service.PatternService;
import jakarta.servlet.http.HttpServletRequest;
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

    /** steamId из JWT (атрибут ставит JwtAuthenticationFilter); null — токена нет или он невалиден. */
    private ResponseEntity<?> requireAdmin(HttpServletRequest request) {
        String steamId = (String) request.getAttribute(JwtAuthenticationFilter.STEAM_ID_ATTR);
        if (steamId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "authentication required"));
        }
        if (!isAdmin(steamId)) {
            return ResponseEntity.status(403).body(Map.of("error", "admin only"));
        }
        return null;
    }

    @PostMapping("/reload")
    public ResponseEntity<?> reload(HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdmin(request);
        if (denied != null) return denied;
        int keys = service.reload();
        return ResponseEntity.ok(Map.of("reloaded", true, "keys", keys, "skins", service.listSkins().size()));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ResponseEntity.ok(Map.of(
                "skins", service.listSkins(),
                "skinsNormalized", service.listSkinsNormalized(),
                "totalSeeds", service.totalSeeds()
        ));
    }
}
