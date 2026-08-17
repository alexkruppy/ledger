# Ledger — Distributed Ledger & Payment Gateway

Сервис международных переводов и цифровых кошельков. Мультивалютная платёжная
платформа: регистрация кошельков, пополнение, P2P-переводы внутри системы и
вывод средств через имитацию внешнего банковского эквайринга.

**Стек:** Java 21 (Virtual Threads) · Spring Boot 3.3 · Spring Data JPA ·
PostgreSQL · Redis · Apache Kafka · Liquibase · Micrometer/Prometheus/Grafana ·
Testcontainers

---

## Ключевые инженерные решения

### 1. Модуль кошельков (Wallet Core) — защита от Double-Spending

- **Pessimistic locking** (`SELECT … FOR UPDATE`) для всех критических операций
  с балансом: `WalletRepository#findByIdForUpdate` блокирует строку кошелька, и
  все конкурентные списания сериализуются на этой блокировке.
- **Optimistic locking** (`@Version`) на `wallets`, `transfers`,
  `payment_transactions`, `outbox_events`.
- **Event Sourcing / Ledger**: каждая операция пишет запись в `ledger_entries`
  (`DEBIT`/`CREDIT`, `balance_after`). Материализованный `wallets.balance`
  обновляется в той же ACID-транзакции, инвариант
  «баланс = сумма записей» соблюдается всегда, а уникальный
  `(wallet_id, operation_key)` исключает повторную проводку (double-post).

### 2. Transactional Outbox

- События пишутся в `outbox_events` **в той же транзакции**, что и движение
  денег → БД и Kafka не могут разойтись.
- `OutboxPoller` (Spring Scheduled) забирает пачку событий через
  `SELECT … FOR UPDATE SKIP LOCKED` (безопасно для нескольких инстансов),
  публикует в Kafka с `acks=all` + `enable.idempotence=true` и только после
  этого помечает событие `PUBLISHED`.
- Неудачи повторяются с экспоненциальным backoff до `max-attempts`, далее
  событие переходит в `FAILED` и видно в метриках.

### 3. Идемпотентный REST API

- Заголовок `Idempotency-Key` на `POST /api/transfers`,
  `POST /api/payments/wallets/{id}/topup|withdraw`.
- `IdempotencyService` (Redis) кэширует **полный ответ первого запроса**
  (`status` + JSON-тело) на 24 часа. Повторный запрос с тем же ключом не
  выполняет операцию и возвращает закэшированный результат.
- Конкурентные дубликаты: `SETNX`-claim + короткое ожидание первого запроса;
  при превышении таймаута — `409`. Дубликат ключа в БД (`UNIQUE` по
  `idempotency_key`) — последний рубеж обороны.

### 4. Payment Gateway и Saga (Orchestration)

- Внешний эквайринг — отдельный сервис `mock-gateway` (имитация банка:
  задержки 200–900 мс, отказ для сумм, кратных 100).
- **Saga с компенсацией**:
  - *Withdraw*: шаг 1 — резервирование (DEBIT, статус `PENDING`);
    шаг 2 — запрос в gateway; при успехе `COMPLETED`, при ошибке —
    компенсирующая проводка CREDIT (`payment:{id}:refund`) и `ROLLED_BACK`.
  - *Top-up*: кредит применяется только после подтверждения gateway.

### 5. Безопасность (production-подход)

- Access-token: короткоживущий JWT (15 мин), в `Authorization: Bearer`.
- Refresh-token: **opaque-токен в Redis с ротацией и детекцией повторов**
  (OWASP): при ротации старый токен инвалидируется; повторное использование
  отозванного токена отзывает всю «семью» (`rtf:{familyId}`).
- BCrypt(cost=12), rate-limit входа и переводов (Redis fixed-window),
  блокировка аккаунта после 5 неудачных входов.

### 6. Observability

- Micrometer: `http.server.requests` (percentile histograms), кастомные метрики
  `ledger.gateway.latency`, `ledger.outbox.*`.
- Logback: структурированный JSON (`logstash-logback-encoder`) через профиль
  `json-log`.
- Prometheus + Grafana: дашборд (heap, RPS, p95/p99 latency, error ratio,
  outbox throughput, latency gateway) — см. `monitoring/`.

---

## Схема БД (кратко)

| Таблица | Назначение |
|---|---|
| `users` | пользователи (USER/ADMIN/SYSTEM), блокировки, роли |
| `wallets` | кошельки по валютам; `balance` + `version` (optimistic) |
| `ledger_entries` | событийный журнал DEBIT/CREDIT, `balance_after`, `operation_key` (UK) |
| `transfers` | P2P-переводы, FX-курс, комиссия, статусы |
| `payment_transactions` | Saga-платежи (TOP_UP/WITHDRAW) через внешний gateway |
| `exchange_rates` | курсы (EUR pivot), обновляются админом |
| `outbox_events` | transactional outbox для событий в Kafka |

Миграции — Liquibase: `src/main/resources/db/changelog/`.

---

## Запуск

```bash
# 1. Собрать
./mvnw -q -DskipTests package

# 2. Инфраструктура (Postgres, Redis, Kafka)
docker compose up -d postgres redis kafka

# 3. Mock-эквайринг (порт 8081)
docker compose --profile app up -d --build mock-gateway

# 4. Приложение (порт 8080)
DB_URL=jdbc:postgresql://localhost:5432/ledger \
DB_USERNAME=ledger DB_PASSWORD=ledger \
REDIS_HOST=localhost KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
GATEWAY_BASE_URL=http://localhost:8081 \
mvn spring-boot:run
```

Всё приложение целиком (включая mock-gateway, Prometheus и Grafana):

```bash
docker compose --profile app --profile monitoring up -d --build
```

| Сервис | URL |
|---|---|
| SPA + API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Prometheus | http://localhost:9090 |
| Grafana (admin/admin) | http://localhost:3000 |

Админ для теста: `admin@ledger.com` / `admin123`.

---

## REST API

### Auth

```
POST /api/auth/register   {email, password, firstName?, lastName?}
POST /api/auth/login      {email, password}
POST /api/auth/refresh    {refreshToken}          → ротация refresh-токена
POST /api/auth/logout     {refreshToken}
GET  /api/auth/me
```

### Wallets (JWT)

```
POST /api/wallets                    {currency: "EUR"}
GET  /api/wallets
GET  /api/wallets/{id}
GET  /api/wallets/{id}/ledger?page=0&size=20
```

### Transfers (JWT) — идемпотентны

```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"fromWalletId":1,"toWalletId":2,"amount":50.00}'
```

### Payments (JWT) — saga через внешний gateway

```bash
curl -X POST http://localhost:8080/api/payments/wallets/1/topup \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"amount":50.00}'

curl -X POST http://localhost:8080/api/payments/wallets/1/withdraw \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"amount":30.00,"bankAccount":"DE00..."}'
```

### Публичное / админ

```
GET  /api/exchange-rates?base=EUR
PUT  /api/admin/exchange-rates          (ADMIN)
DELETE /api/admin/exchange-rates/{base}/{quote}  (ADMIN)
GET  /api/admin/transfers               (ADMIN)
GET  /api/admin/payments                (ADMIN)
```

---

## Kafka

| Топик | События |
|---|---|
| `ledger.transfers` | TRANSFER_COMPLETED |
| `ledger.payments` | PAYMENT_COMPLETED / PAYMENT_FAILED |
| `ledger.wallets` | WALLET_CREATED |
| `ledger.notifications` | (реплика, демонстрирует потребление) |
| `ledger.notifications.retry` / `.dlt` | retry-топик и dead-letter queue |

Продюсер: `acks=all`, `enable.idempotence=true`. Консьюмеры — manual ack,
at-least-once (получатели должны быть идемпотентны).

---

## Тестирование

```bash
# Юнит-тесты (без Docker)
./mvnw test -Dtest='FxServiceTest'

# Интеграционные (нужен Docker: Postgres + Redis + Kafka + WireMock-шлюз)
./mvnw verify
```

Покрытие: авторизация (включая детекцию повтора refresh-токена),
кросс-валютные переводы, идемпотентность, **конкурентные списания
(double-spend)**, saga top-up/withdraw с компенсацией, outbox → Kafka E2E.

---

## Структура проекта

```
ledger/
├── mock-gateway/            # имитация внешнего банковского эквайринга
├── monitoring/              # prometheus.yml, Grafana provisioning + dashboard
├── src/main/java/com/ledger/
│   ├── controller/ service/ repository/ model/ dto/
│   ├── security/            # JWT + refresh rotation (Redis) + rate limiting
│   ├── messaging/           # outbox poller, Kafka producer/consumer, retry/DLT
│   ├── gateway/             # клиент mock-эквайринга (RestClient)
│   └── exception/
├── src/main/resources/
│   ├── db/changelog/        # Liquibase-миграции
│   ├── static/              # SPA (HTML/CSS/JS)
│   └── application.yml
└── docker-compose.yml
```
