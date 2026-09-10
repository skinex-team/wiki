package com.skinex.pattern.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Validates JWT tokens issued by NextAuth (HS256).
 * Shared secret is configured via skinex.frontend.jwt.secret / NEXTAUTH_SECRET.
 * (Копия из users-сервиса com.skinex.users.auth.JwtUtil — дублируется в каждом сервисе.)
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${skinex.frontend.jwt.secret:}")
    private String secret;

    private SecretKey key;

    private static final String DEFAULT_SECRET = "change-me-to-a-random-secret-at-least-32-chars";

    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()) {
            log.warn("skinex.frontend.jwt.secret / NEXTAUTH_SECRET is not set. New frontend JWT validation will reject all tokens.");
            this.key = null;
        } else if (DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "skinex.frontend.jwt.secret is set to the default value. Set NEXTAUTH_SECRET env to a real secret — default-secret JWTs are publicly forgeable.");
        } else {
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
    }

    public Optional<Claims> validateToken(String token) {
        if (key == null || token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> extractSteamId(String token) {
        return validateToken(token)
                .map(claims -> claims.get("steamId", String.class))
                .or(() -> validateToken(token)
                        .map(claims -> claims.get("sub", String.class)));
    }
}
