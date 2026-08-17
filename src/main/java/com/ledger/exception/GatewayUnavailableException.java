package com.ledger.exception;

import org.springframework.http.HttpStatus;

public class GatewayUnavailableException extends ApiException {

    public GatewayUnavailableException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }
}
