package com.ledger.gateway;

import java.math.BigDecimal;

public record GatewayChargeRequest(BigDecimal amount, String currency, String reference) {
}
