# ADR-0001: Integration with Salesforce

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

Every payment notification must end as a `Premium_Payment__c` record in Salesforce, exactly once in business terms and never in a state that contradicts the provider (FR3, FR4, FR5). Salesforce constrains how we can do it (`reference/salesforce/API_CONSTRAINTS.md`):

- **Availability ≈ 97%**: about 21.6 hours per month of unplanned outages, in windows of 5 to 90 minutes.
- **API budget of 150,000 calls per rolling 24 h** for this integration, inside an **org-wide limit shared** with the contact center and partners. Exceeding the org-wide limit blocks **every** integration (`403 REQUEST_LIMIT_EXCEEDED`).
- **At most 25 concurrent requests**. Single-record upsert p95 ≈ 1.2 s, up to 8 s under load. A 200-record composite upsert counts as **one** call, p95 ≈ 4 s.

Our side of the equation:

- **Volume:** up to 120,000 notifications per day at month-end, with bursts of **40 per second** for 15 minutes (NFR1).
- **Provider timeouts:** 5 s (Andina) and 10 s (Quetzal, Austral). A notification is accepted only when we answer `2xx`, and an accepted one may never be lost (NFR3, NFR4).
- **Freshness:** 95% within 5 minutes, 99.5% within 1 hour (NFR2).
- **Salesforce automation:** a wrong `Status__c` reinstates or suspends a customer's policy.

The decision has two parts: **synchronous or asynchronous**, and **per record or in batches**.

## Options considered

### Option 1: Synchronous, per record (Salesforce call inside the webhook request)
- Pros:
  - Simplest design, with no queue or extra state.
  - The provider gets a `2xx` only after the payment is in Salesforce.
- Cons:
  - Fails during every Salesforce outage. For up to 90 minutes every notification is rejected, and we depend on each provider's retry policy. Andina retries only 5 times in 24 h.
  - Latency up to 8 s under load does not fit Andina's 5 s timeout.
  - The 40/s burst needs about 48 concurrent requests, which is above the cap of 25.
  - Uses one call per notification: ≈ 116,000 calls on a peak day, **78% of the budget** before any retry.

### Option 2: Asynchronous, per record (queue, then one upsert per message)
This is what v1 does (`reference/incident/INC-0412.md`).
- Pros:
  - The queue decouples providers from Salesforce, so outages no longer reject notifications.
  - Simple consumer logic.
- Cons:
  - Same API cost as option 1: **78% of the budget** on a peak day. In INC-0412, retries alone took v1 to 66% on an ordinary day.
  - Throughput is limited to about 21 records/s with 25 concurrent requests, so the burst builds a backlog.
  - Without explicit error classification, every `400` is retried until the delivery count is exhausted.

### Option 3: Asynchronous, batched with composite upsert (up to 200 records per call)
- Pros:
  - The queue absorbs outages and bursts.
  - The cost has an upper bound: one batch per country every 10 seconds means **at most 25,920 calls per day (17%)**, regardless of volume.
  - Throughput is up to 200 records per call, so a 42,000-event backlog drains in about 3 minutes.
  - `allOrNone: false` keeps records independent.
- Cons:
  - Adds up to 10 s of latency, the batch window.
  - More logic: grouping, one record per `paymentKey` per batch, and per-record result handling.
  - A batch of 200 records triggers the Salesforce flow 200 times at once.

### Option 4: Bulk API 2.0 (asynchronous ingest jobs)
- Pros:
  - Designed for very large volumes, thousands of records per job.
- Cons:
  - Each job needs several calls: create, upload, close, and poll until finished.
  - Jobs are processed by Salesforce in its own queue, with latency of minutes, which puts the 5-minute freshness target at risk.
  - Results must be downloaded to know which records failed.
  - Built for nightly loads, not near real time. It would rebuild today's nightly import in a different form.

## Decision

**Option 3: asynchronous, batched composite upserts by external id (`Payment_Key__c`).**

How it works:

1. **Asynchronous.** `payment-intake` publishes the canonical event to Service Bus and answers the provider at once. Salesforce is never on the provider's critical path.
2. **One consumer per country** (`sf-writer-mx`, `-co`, `-cl`). A problem in one country does not block the others.
3. **Batching rule.** Send when there are **200 records or 10 seconds** have passed, whichever comes first. A batch never contains two records with the same `paymentKey`.
4. **Upsert by `Payment_Key__c`** with `allOrNone: false`. The same upsert repeated always updates the same record, never a new one.
5. **Ordering rule before writing.** Only events newer than the stored state are sent (SOLUTION_DESIGN §4.3), so a late event never overwrites a refund.
6. **Protection of the shared API budget:**
   - Count our own calls in a rolling 24 h window. Alert at 60%, widen the batch window to 60 s at 80%, stop at 95%.
   - At most 5 concurrent calls, out of the 25 allowed.
   - Cache the OAuth token for its full lifetime (≈ 2 h).
   - On `403 REQUEST_LIMIT_EXCEEDED`, pause **all** countries and probe every 5 minutes.
   - `400` data errors go to the DLQ at once and are **never** retried.
7. **Living with 97% availability:**
   - A circuit breaker per country stops reading the queue after 5 consecutive `5xx` responses and probes every 30 s.
   - Events wait in Service Bus without spending deliveries, and nothing is rejected to the provider.
   - After recovery, the backlog drains at about 250 records/s.

The figures behind this decision are in SOLUTION_DESIGN §6.

## Consequences

- **Positive:**
  - Provider notifications are never rejected because Salesforce is down. NFR3 no longer depends on Salesforce.
  - API usage has an upper bound of **17%** of the budget on any day, compared with 78% or more for one call per record. That leaves room for retries, replays and the other integrations of the org.
  - The 40/s burst and a 90-minute outage are absorbed with a few calls.
  - Countries are isolated, so a failure like INC-0412 stays in one country.
- **Negative:**
  - Up to 10 s of extra latency per event. This is acceptable against the 5-minute target, but it is not instant.
  - More moving parts: Service Bus sessions, a state store (Cosmos DB), batching and per-record result handling. More to build, test and operate.
  - **Freshness is bounded by Salesforce.** If outages last longer than 60 minutes, the 99.5%-within-1-hour target can drop to about 99.0%. No integration design can fix this (SOLUTION_DESIGN §6.5).
  - A defect in the mapping can affect up to 200 records in one call before it is detected.
  - Salesforce shows the payment slightly later than the provider. During that gap the contact center must use the Payment Ops API to confirm a payment.
- **Risks and how they are mitigated:**

| Risk | Mitigation |
|---|---|
| The Salesforce flow cannot handle 200 records per transaction or the drain rate | Confirm with the Salesforce team (assumption 6). The batch size and drain concurrency are configuration values, not code. |
| Another integration consumes the org-wide limit and we get `403` | Pause all countries, probe every 5 minutes, and alert both teams. Events wait safely in the queue. |
| Older and newer events of the same payment race to Salesforce | Service Bus sessions per `paymentKey`, plus a conditional write of `payment-state` (SOLUTION_DESIGN §4.4) |
| The budget guard counts only our calls, not the org-wide usage | Agree on an alert with the Salesforce team based on org-wide usage. Our guard keeps our share predictable. |
| A long Salesforce outage leaves customers suspended although they paid | Freshness alerts per country, and payment status visible to the contact center through the Payment Ops API |
