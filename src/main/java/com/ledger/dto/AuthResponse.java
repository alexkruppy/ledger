package com.ledger.dto;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        UserDto user) {

    public static AuthResponse of(String accessToken, long expiresInSeconds, String refreshToken, UserDto user) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, refreshToken, user);
    }
}
