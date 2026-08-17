--liquibase formatted sql
--changeset alexkruppy:0002_seed

-- Base exchange rates (EUR pivot). 1 EUR = rate units of quote currency.
INSERT INTO exchange_rates (base_currency, quote_currency, rate) VALUES
    ('EUR', 'EUR', 1.000000000000),
    ('EUR', 'USD', 1.084000000000),
    ('EUR', 'GBP', 0.850000000000),
    ('EUR', 'CHF', 0.945000000000),
    ('EUR', 'JPY', 162.500000000000),
    ('EUR', 'RUB', 96.500000000000);

-- System fee-account owner (holds collected fees).
INSERT INTO users (email, password_hash, first_name, last_name, role, status)
VALUES ('fees@ledger.internal', '!disabled!', 'Ledger', 'Fees', 'SYSTEM', 'ACTIVE');
