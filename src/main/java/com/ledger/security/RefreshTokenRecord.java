package com.ledger.security;

/**
 * Opaque refresh token with rotation and reuse detection.
 * <p>Token format: {@code familyId.tokenId}. Family groups a chain of rotated
 * tokens. On refresh the old token is invalidated and a new one (same family)
 * is issued. If a revoked token is replayed, the whole family is revoked —
 * this is the OWASP recommended refresh-token strategy.
 */
public record RefreshTokenRecord(String familyId, String tokenId, Long userId, long expiresAtEpochSeconds) {

    public String asString() {
        return familyId + "." + tokenId;
    }

    public static String[] split(String token) {
        int dot = token.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("Malformed refresh token");
        }
        return new String[]{token.substring(0, dot), token.substring(dot + 1)};
    }
}
