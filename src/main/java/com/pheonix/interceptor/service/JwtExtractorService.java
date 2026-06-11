package com.pheonix.interceptor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Extracts claims from the raw JWT bearer token present in the HTTP
 * {@code Authorization} header.
 *
 * <p>This service performs <strong>no signature verification</strong> — it simply
 * Base64-decodes the payload segment.  Signature validation is assumed to have
 * already been performed by a gateway or Spring Security filter upstream.
 */
@Service
public class JwtExtractorService {

    private static final Logger log = LoggerFactory.getLogger(JwtExtractorService.class);

    private static final String BEARER_PREFIX  = "Bearer ";
    private static final String AGENT_ID_CLAIM = "agent-id";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Extracts the {@code agent-id} claim from the Authorization header JWT.
     *
     * @param authorizationHeader value of the {@code Authorization} HTTP header
     * @return the agent ID, or {@code "unknown"} if it cannot be determined
     */
    public String extractAgentId(String authorizationHeader) {
        return extractFromHeader(authorizationHeader, AGENT_ID_CLAIM);
    }

    /** Shared helper: strips the Bearer prefix and delegates to {@link #extractClaim}. */
    private String extractFromHeader(String authorizationHeader, String claimName) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return "unknown";
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return extractClaim(token, claimName);
    }

    /**
     * Decodes the JWT payload segment and returns the value of the requested claim.
     *
     * @param jwt       raw JWT token (three dot-separated Base64 segments)
     * @param claimName name of the claim to extract
     * @return claim value as {@code String}, or {@code "unknown"} on any error
     */
    @SuppressWarnings("unchecked")
    public String extractClaim(String jwt, String claimName) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return "unknown";
            }
            // JWT payload is the second segment, Base64URL-encoded (no padding)
            String payloadJson = new String(Base64.getUrlDecoder().decode(addPadding(parts[1])));
            Map<String, Object> claims = objectMapper.readValue(payloadJson, Map.class);
            Object value = claims.get(claimName);
            return value != null ? value.toString() : "unknown";
        } catch (Exception e) {
            log.warn("Failed to extract claim '{}' from JWT: {}", claimName, e.getMessage());
            return "unknown";
        }
    }

    /** Adds missing Base64 padding characters so the standard decoder can handle it. */
    private static String addPadding(String base64) {
        int mod = base64.length() % 4;
        if (mod == 2) return base64 + "==";
        if (mod == 3) return base64 + "=";
        return base64;
    }
}
