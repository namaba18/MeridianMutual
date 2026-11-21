# Operations — Premium Payment Notifications

How we know the integration works, per country, and who acts when it does not. Components and queues: [`SOLUTION_DESIGN.md`](../design/SOLUTION_DESIGN.md). The alerts below are code in [`infra/modules/alerts.bicep`](../../infra/modules/alerts.bicep).

## 1. SLIs and SLOs

Every SLO is measured **per country** (MX, CO, CL), over a **rolling 28 days**, on **unique payment events** (one per `eventId`, so duplicates do not count twice). Each country has its own error budget, so a bad week in Colombia cannot hide behind a good week in Mexico.

| # | SLI | How it is measured | SLO | From |
|---|---|---|---|---|
| 1 | **Freshness 5 min**: payments visible in Salesforce within 5 min | `recordedAt − receivedAt` (`writer.freshness.seconds`) | **≥ 95%** | NFR2 |
| 2 | **Freshness 1 h**: same, within 1 hour | same | **≥ 99.5%** | NFR2 |
| 3 | **Loss**: notifications answered `2xx` that, after 24 h, are missing or still `RECEIVED`/`FAILED` | Daily reconciliation in `payment-ops`: intake log lines with outcome `ACCEPTED` vs. Cosmos DB `notifications`, matched by `correlationId`; `QUARANTINED` vs. the quarantine queue | **0** (no budget: each one is an incident) | NFR3 |
| 4 | **Completeness**: accepted payments recorded in Salesforce within 24 h | `RECORDED` or `SKIPPED` within 24 h; dead-lettered and quarantined count as failures | **≥ 99.9%** | NFR3 |
| 5 | **Intake availability**: authentic webhooks answered `2xx` within 3 s | `intake.notifications` and `http.server.requests` duration, per provider (`401` and `413` excluded) | **≥ 99.9%** | NFR3, NFR4 |

- **Freshness clock.** It starts at `receivedAt`, set by `payment-intake`, and never uses the provider's clock. It stops at the Salesforce response. An event skipped as stale counts as on time, because Salesforce already shows the right state. A dead-lettered or quarantined payment counts as late until it is recorded.
- **Two views.** The SLO is judged on the raw number. A second view excludes Salesforce's declared unavailability, and shows whether a miss was ours or Salesforce's (SOLUTION_DESIGN §6.5).
- **Why SLI 5.** A `503` is not a loss, because the provider retries. But Andina gives up after 5 attempts in 24 h, so a long run of `503` becomes a loss.

**Error budget at normal volume** (28 days):

| | Mexico (≈189,000 events) | Colombia (≈168,000) | Chile (≈63,000) |
|---|---|---|---|
| May exceed 5 min (5%) | 9,450 | 8,400 | 3,150 |
| May exceed 1 h (0.5%) | 945 | 840 | 315 |
| May miss 24 h (0.1%) | 189 | 168 | 63 |

For scale: in INC-0412, Colombia dead-lettered 4,312 notifications in 32 hours, about 25 times its 28-day completeness budget. **Budget policy:** at 50% of a budget, that country's next work items are reliability fixes. When the whole budget is used, only fixes ship to that country. The SLIs per country go weekly to the product owner and the Salesforce team (NFR7).

## 2. Instrumentation

### 2.1 How the correlation id travels

1. **API Manager → `payment-intake`.** The gateway forwards its request id in `X-Request-Id` (assumption 8). `payment-intake` creates the `correlationId` (UUID) on arrival, before the signature check, and logs both ids, so gateway and application logs can be joined. The request id comes from outside, so it is logged only if it looks like an id. It returns `X-Correlation-Id` on `200` and `503`, as the webhook contract defines, so the provider's support team can quote it. A `401` gets none: nothing is stored for it.
2. **Service Bus.** The id is in the event field `correlationId` and in the message property `CorrelationId`. The Azure SDK adds the W3C trace context to the message properties, so the trace continues across the queue.
3. **Cosmos DB.** The `notifications` document stores it with the `eventId` and the provider ids.
4. **`salesforce-writer`.** The id is in the logging context of each message. A composite call carries up to 200 events, so its span is linked to each message's span, and the batch log line lists their `eventId`s.
5. **Salesforce** has no field for it. `Payment_Key__c` and `Last_Event_Id__c` lead back to the `notifications` document, and from there to the trace.
6. **DLQ, quarantine, replay.** Messages keep their `correlationId`, also when replayed; each replay is recorded in `replay-audit`. The Payment Ops API finds the `correlationId` from any provider id (FR7).

### 2.2 What we log, trace and measure

- **Logs.** JSON to the platform Log Analytics workspace, built from an **allowlist** of fields: service, country, provider, `correlationId`, `eventId`, provider ids, outcome, reason, HTTP status, `sfErrorCode`, `deliveryCount`, duration. One `INFO` line per notification. `WARN` for an expected failure with a reason. `ERROR` only for a defect.
- **Traces.** The OpenTelemetry Java agent exports to Application Insights. Sampling is configured at 100%, not the agent's default rate limit: the volume is small, and the missing trace is always the one you need. Spans cover the webhook, the signature check, Cosmos DB, Service Bus, and each Salesforce call (status and record count, never the body).
- **Metrics**, all with a `country` or `provider` dimension:

| Metric | Source | Used for |
|---|---|---|
| `intake.notifications{provider,outcome,reason}` | `payment-intake` | Accepted, duplicate, quarantined, `401`, `503` (SLIs 3, 5) |
| `writer.records{country,outcome}` | `salesforce-writer` | Recorded, skipped, failed, dead-lettered (SLI 4) |
| `writer.freshness.seconds{country}` | `salesforce-writer` | SLIs 1 and 2 |
| `writer.salesforce.responses{country,status,errorCode}` | `salesforce-writer` | A sudden rise of one error code, such as `INVALID_FIELD` |
| `writer.salesforce.calls.rolling24h`, circuit state | `salesforce-writer` | API budget guard (NFR5) |
| `ops.notifications.stuck{country}` | `payment-ops`, every 15 min | Accepted payments not recorded after 1 h |
| Queue, DLQ and quarantine depth | Service Bus | Safety net if our own metrics stop |

### 2.3 What we never log

These never appear in logs, traces, metric dimensions or alert texts, at any level, `DEBUG` included:

- **Payer personal data** (NFR6): names, national ids, emails, phone numbers.
- **Request or response bodies**, from providers or from Salesforce: any of them can carry payer data.
- **Secrets and tokens**: webhook secrets, signature headers, Salesforce and IdP tokens, payment tokens (`tok_…`).
- **Policy numbers and raw `referencia` values.** They identify a person in Salesforce, so we log `eventId` and provider ids instead. Salesforce errors quote the value (`external ID: 000215560 not found`), so only `sfErrorCode` and the field name are logged.

**Enforcement.** Log lines are built from the allowlist, never from a payload's `toString()`. To diagnose a format problem, log the **shape** of a field (for example `referenciaShape=DIGITS_9`), never its value. Tests capture the logs and assert that no payer field appears (`practical/`, NFR6). The tracing agent does not capture headers or bodies.

## 3. Alerts

**Page** goes to the on-call engineer of the Payments Integration squad, 24×7, through the paging tool. **Notify** goes to the squad channel and is handled within the working day. In test, both routes go to the squad channel so the alerts themselves can be tested. In dev, alerts are disabled. Per-country alerts are **generated from the list of countries**: in INC-0412, only Mexico had a DLQ alert.

| Alert | Condition | Sev | Route | First check |
|---|---|---|---|---|
| Payments dead-lettered (per country) | Any, in 5 min | 1 | Page | Reason codes in the Payment Ops API |
| Notifications quarantined (per country) | Any, in 5 min | 2 | Page | Reason codes; did the provider change its format or its API version? |
| Freshness p95 over 5 min (per country) | p95 of the last 15 min, in 2 consecutive checks (every 5 min) | 2 | Page | Salesforce status, circuit breaker, API usage |
| Payments stuck over 1 h (per country) | `ops.notifications.stuck > 0` | 2 | Page | Writer running? Salesforce outage over 60 min? |
| Intake unavailable | Over 1% of webhooks get `503` in 5 min | 1 | Page | Service Bus health (ADR-0003) |
| Invalid signatures (per provider) | Over 20 `401` in 15 min | 2 | Notify | Secret rotation, or someone probing |
| Salesforce API budget | Over 60% of 150,000 calls in 24 h; writers slow down at 80% and stop at 95% | 2 | Notify | Which country and error code consume the calls |
| DLQ / quarantine not empty (platform) | Any message in a DLQ or in `payments-quarantine` | 2 | Notify | Safety net, independent of our telemetry |
| Cosmos DB throttling | Over 10 `429` in 15 min | 3 | Notify | Autoscale ceiling, query pattern |

The on-call engineer owns the incident and brings in other teams. The **Salesforce team** is called for outages, the org-wide API limit or validation errors after a release. The **platform team** is called for Azure services. The **country's contact-center lead** is told when that country's payments are more than 1 h late, so agents can confirm payments in the Payment Ops API meanwhile.

With these alerts, INC-0412 would have paged on Saturday at 23:46, about five minutes after the first dead letter. The contact center reported it on Monday at 08:12.
