package com.ledger.security;

import org.springframework.security.authentication.DisabledException;

public class RefreshTokenReuseException extends DisabledException {

    private final String familyId;

    public RefreshTokenReuseException(String familyId) {
        super("Refresh token is no longer valid. Re-authentication required.");
        this.familyId = familyId;
    }

    public String getFamilyId() {
        return familyId;
    }
}
