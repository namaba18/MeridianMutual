# ADR-0002: Implementation stack and data store

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

Two decisions are needed:

1. **Integration stack** for `payment-intake`, `salesforce-writer` and `payment-ops`. MuleSoft (Mule 4) is Meridian's reference stack, and Java 21 is accepted with justification.
2. **Data store** for the state we keep outside Salesforce: notification status, latest state per payment, and replay audit (SOLUTION_DESIGN §7).

What drives both decisions:

| Driver | What it requires |
|---|---|
| FR1 | HMAC over the **raw bytes** of the body, constant-time comparison, timestamp windows, several signatures during secret rotation |
| FR4, FR5 | A lookup by business key on every notification, and a **conditional write** (compare-and-set) of the payment state |
| NFR1 | 15,000 notifications per day, ×8 at month-end, with bursts of 40/s. The load is spiky, not constant. |
| NFR4 | Answers within 5 s (Andina): no cold starts on the webhook path |
| NFR6 | Secrets only in Key Vault, **managed identities** with least privilege, private networking |
| ADR-0001 | Service Bus sessions per payment, batching across sessions, per-country circuit breaker and budget guard |
| Retention | Different retention per kind of data, with as little personal data as possible |
| Team | The squad's existing prototype is Java 21 / Spring Boot 3 (PR-214) |

## Options considered

### Stack

#### Option S1: Mule 4 on the integration platform (DataWeave, MUnit)
- Pros:
  - Meridian's reference stack, supported by the integration platform team.
  - DataWeave is excellent for payload mapping.
  - Ready-made connectors and native API Manager policies.
- Cons:
  - The Mule runtime runs on the integration platform, **outside Azure**, so it cannot use **managed identities**. Reaching Service Bus, Cosmos DB and Key Vault would need service-principal secrets or SAS keys, which goes against NFR6.
  - The hard parts of this design are not mapping. They are:
    - HMAC over the exact raw bytes;
    - Service Bus **sessions** with batching across sessions;
    - Cosmos DB conditional writes;
    - a circuit breaker and budget guard per country.

    All of these need custom Java components inside Mule, or fall outside what the standard connectors support.
  - MUnit and the Enterprise repositories need credentials that CI may not have.
  - Runtime licensing (vCores) is paid for capacity sized for the month-end peak.

#### Option S2: Java 21 + Spring Boot 3 on Azure Container Apps
- Pros:
  - Runs **inside Azure**: managed identities through `DefaultAzureCredential`, private endpoints, and Key Vault read at runtime with the app's own identity. No secrets in configuration.
  - First-class Azure SDKs for everything ADR-0001 needs: Service Bus sessions and scheduled messages, Cosmos DB ETag conditional writes and native TTL.
  - Full control of signature verification over raw bytes (`MessageDigest.isEqual` for constant-time comparison).
  - Same language and framework as the squad's prototype, so skills and part of the code can be reused.
  - Container Apps provides scaling rules, internal-only ingress in a VNet and minimum replicas (no cold start). `payment-intake` scales on HTTP concurrency. The writers run with a fixed replica count, because Salesforce, not compute, bounds their throughput (SOLUTION_DESIGN §6.3). Billing is per use, so the cost follows the month-end peaks.
  - JUnit 5 and Testcontainers run in any CI without special credentials.
- Cons:
  - **Deviates from Meridian's reference stack.** The integration platform team does not run it, so the squad owns runtime, patching and on-call for these services.
  - More code to write and maintain: no DataWeave, no ready-made connectors.
  - Another container platform to govern next to the integration platform.

#### Option S3: Java 21 on Azure Functions
- Pros:
  - Native Service Bus trigger with sessions.
  - Serverless operation with very little infrastructure.
- Cons:
  - Batching **across sessions** into one 200-record call with a 10-second window does not fit the per-trigger execution model.
  - The webhook path on the consumption plan has cold starts that do not fit Andina's 5 s. The Premium plan removes them, but costs a fixed amount like an always-on host.
  - Spring Boot does not run naturally on Functions.

#### Option S4: Java 21 on Azure Kubernetes Service
- Pros:
  - Maximum control and flexibility.
- Cons:
  - Cluster operations (upgrades, node pools, ingress, policies) are out of proportion for three small services.

### Data store

#### Option D1: Azure Cosmos DB for NoSQL
- Pros:
  - The access pattern is almost entirely **point reads and writes by key**. Each costs about 1 RU with a single-digit-millisecond latency.
  - **ETag optimistic concurrency** gives the compare-and-set the ordering rule needs (SOLUTION_DESIGN §4.4).
  - **Native TTL per container**, so retention needs no purge jobs.
  - **Autoscale throughput** follows the month-end peak: it scales up for 4 days and down for 26.
  - Partitioning by `paymentKey` has no hot partitions. Microsoft Entra ID data-plane roles allow managed identities without keys.
- Cons:
  - Operations queries by provider event id cross partitions. That is fine for a few queries per day, but it is not a reporting database.
  - The request-unit (RU) model needs care: indexing policy and document size directly affect cost.
  - Less familiar than SQL to some of the team, and specific to Azure (lock-in).

#### Option D2: Azure SQL Database
- Pros:
  - A `UNIQUE` constraint on the business key gives idempotency directly, and transactions are available.
  - Rich SQL for operations and ad-hoc analysis. The prototype already uses it.
- Cons:
  - Retention needs scheduled purge jobs.
  - Scaling is mostly vertical: it has to be sized for the month-end peak or resized every month.
  - The serverless tier's auto-pause adds a resume delay on the webhook path, so it would have to be disabled.
  - The access pattern is key-value. Relational features would mostly go unused.

#### Option D3: Azure Table Storage
- Pros:
  - Very cheap, key-value, with ETag concurrency.
- Cons:
  - **No TTL**: retention needs jobs.
  - **No secondary indexes**: looking up by provider event id (FR7) needs extra "index tables" kept in sync by our own code.
  - Weaker query capabilities for operations.

#### Option D4: Azure Cache for Redis
- Pros:
  - Very fast lookups for duplicates.
- Cons:
  - Not a durable store. Losing the payment state would break the ordering rule (FR5).
  - Rejected as the store of record.

## Decision

- **Stack: Java 21 + Spring Boot 3 on Azure Container Apps (option S2).** Three services (`payment-intake`, `salesforce-writer`, `payment-ops`) run in a Container Apps environment with internal-only ingress in the VNet, published through the API Manager. Integration with Azure resources uses managed identities only.
- **Data store: Azure Cosmos DB for NoSQL (option D1)**, with containers `notifications`, `payment-state` and `replay-audit` (SOLUTION_DESIGN §7), autoscale throughput and native TTL.

**Why Java wins in this scenario:**
- The difficult parts are reliability mechanics, not mapping.
- Running inside Azure is what makes managed identities, and therefore NFR6, possible without secrets.
- The squad already works in Java.

The platform rule is still respected: every API is published through the API Manager.

**Why Cosmos DB wins:**
- Nearly every access is a point operation by key.
- Compare-and-set with ETag and native TTL are exactly what idempotency, ordering and retention need.
- Autoscale matches a load that is 8 times higher on 4 days of each month.

## Consequences

- **Positive:**
  - No secrets for Azure resources: managed identities and Key Vault only (NFR6).
  - Every mechanism of ADR-0001 (sessions, batching, ETag, circuit breaker) uses first-class SDK features, not workarounds.
  - Reviewers and CI can build and test the code with standard Maven and JUnit 5, without Enterprise credentials.
  - Cost follows the load: per-use compute and autoscale throughput instead of capacity sized all month for the peak.
  - Retention is enforced by the store itself.
- **Negative:**
  - The squad, not the integration platform team, owns runtime, patching, scaling and on-call for these services.
  - Divergence from the reference stack: Mule developers cannot maintain these services without learning Java.
  - Mapping that DataWeave would express in a few lines needs code and tests per provider adapter.
  - Cosmos DB is not good for ad-hoc analysis. Reporting must come from Salesforce or from exported telemetry, not from the operational store.
  - The RU cost model needs ongoing attention: indexing policy, document size and autoscale ceiling.
- **Risks and how they are mitigated:**

| Risk | Mitigation |
|---|---|
| The integration platform team does not support Container Apps services | Agree on ownership up front. The services are published and governed through the API Manager like any other API. Runbooks live in `docs/operations/`. |
| Provider adapters drift between countries | Each provider adapter implements the same two interfaces, `SignatureVerifier` and `PayloadMapper`, with contract tests driven by that provider's samples and test vectors. Adding a country means adding an adapter, not changing the flow (NFR9). |
| An unexpected Cosmos DB query pattern causes RU spikes | Indexing limited to the paths used by FR7, `payment-state` with indexing off, an autoscale ceiling and an alert on throttling (`429`). |
| Lock-in to Cosmos DB | The store is behind repository interfaces, and the data is small and short-lived (180–400 days), so a migration would be cheap. |
