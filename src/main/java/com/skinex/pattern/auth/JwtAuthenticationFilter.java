package com.skinex.pattern.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Extracts and validates JWT from Authorization header for requests coming from the Next.js frontend.
 * Sets a request attribute "steamId" so controllers can use a unified lookup.
 * (Копия из users-сервиса com.skinex.users.auth.JwtAuthenticationFilter.)
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    public static final String STEAM_ID_ATTR = "steamId";
    public static final String AUTHORIZATION_HEADER = "Authorization";

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            Optional<String> steamId = jwtUtil.extractSteamId(token);
            if (steamId.isPresent()) {
                request.setAttribute(STEAM_ID_ATTR, steamId.get());
                log.debug("Authenticated via JWT: {}", steamId.get());
            } else {
                log.debug("Invalid JWT token for request {}", request.getRequestURI());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
