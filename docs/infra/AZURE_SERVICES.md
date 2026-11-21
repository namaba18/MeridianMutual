# Azure services

The services behind [`infra/`](../../infra/), why each one, what was discarded, and how each supports the pillars of the Azure Well-Architected Framework: **Reliability, Security, Cost Optimization, Operational Excellence and Performance Efficiency**. The decisions come from the [solution design](../design/SOLUTION_DESIGN.md) and ADR-0001 to ADR-0003.

## Summary

| Service | Role | SKU / tier | Prod cost / month (USD, approx.) |
|---|---|---|---|
| Azure Container Apps | Runs `payment-intake`, one `salesforce-writer` per country, `payment-ops` | Consumption workload profile, internal environment | ≈ 220 |
| Azure Service Bus | Durable buffer, fan-out to consumers, DLQs, quarantine | **Premium**, 1 messaging unit | ≈ 680 |
| Azure Cosmos DB for NoSQL | Notification status, payment state, replay audit | Provisioned **autoscale**, continuous backup | ≈ 120 |
| Azure Key Vault (×2) | Webhook secrets; Salesforce secret | Standard, RBAC | ≈ 1 |
| Virtual Network, Private Endpoints, Private DNS | Private access to every data service | 4 endpoints, 4 zones | ≈ 30 |
| Managed identities | One per workload, one writer per country | User-assigned | 0 |
| Application Insights, alerts | Traces, metrics, freshness and DLQ alerts | Workspace-based, on the platform Log Analytics | ≈ 50 |
| **Total prod** | | | **≈ 1,100** |

Not included because they already exist at platform level: the API Manager, the Log Analytics workspace (only our ingestion is counted) and the container registry (assumption 12).

---

## Azure Container Apps

**Why.** Three small Java services that must stay warm (Andina's 5 s timeout), run inside a VNet with managed identities, and need no cluster to operate. Container Apps provides internal-only environments, per-app identities, health probes, revisions and zero-downtime deployments, billed per second of use.

**Discarded.**
- **Azure Functions:** batching across Service Bus sessions into one 200-record Salesforce call does not fit the per-trigger model, and the consumption plan's cold starts do not fit 5 s (ADR-0002).
- **AKS:** cluster upgrades, node pools and ingress are out of proportion for three services.
- **App Service:** works, but VNet integration plus private inbound access requires more pieces, and there is no per-second billing for the low-traffic `payment-ops`.

**SKU.** Consumption workload profile. intake 0.5 vCPU / 1 GiB (2–10 replicas in prod), each writer 0.5 vCPU / 1 GiB (1 replica), ops 0.25 vCPU / 0.5 GiB.

| Pillar | How |
|---|---|
| Reliability | Zone-redundant environment in prod; intake keeps 2 replicas across zones; liveness and readiness probes; one writer per country, so a failure stays in one country |
| Security | Internal environment (no public IP); HTTP apps accept only the API Manager subnet; each app runs as its own identity; no secrets in environment variables |
| Cost | Per-second billing; `payment-ops` scales to zero outside prod |
| Operational excellence | Immutable images tagged with the commit SHA; single active revision; logs to the platform workspace |
| Performance | 40/s needs about 2 requests in flight (§6.1); intake scales on HTTP concurrency up to 10 replicas |

## Azure Service Bus — Premium

**Why.** The durable acceptance point of ADR-0003 and the buffer between the providers and Salesforce's 97% availability (ADR-0001). It provides every broker feature the design relies on: **duplicate detection** (§4.2), **sessions** per payment (§4.4), topic **subscriptions with SQL filters** per country and per consumer, **dead-letter queues**, and Entra ID authentication.

**Discarded.**
- **Standard tier:** has topics, sessions and duplicate detection, and costs about USD 10/month, but **no private endpoints and no VNet isolation**. That would put the payment backbone on a public endpoint (NFR6), and dev and test would differ from prod. It is the main cost lever (see below).
- **Event Grid:** push delivery with no sessions, no duplicate detection and limited retention. Built for notifications, not for a durable work queue.
- **Event Hubs:** a partitioned log. No per-message settlement, no dead-lettering and no SQL filters; replay and DLQ handling would have to be rebuilt.
- **Storage queues:** no topics, sessions or duplicate detection.

**SKU.** Premium, **1 messaging unit** in every environment. One unit handles thousands of messages per second, far above the 40/s peak.

| Pillar | How |
|---|---|
| Reliability | Messages are replicated before the send is acknowledged; zone redundancy in prod; expired messages are dead-lettered, never dropped |
| Security | `disableLocalAuth` (no SAS keys), private endpoint only, TLS 1.2; senders and receivers scoped to one topic, subscription or queue |
| Cost | The largest line (see below); a single unit is enough at 8 times the normal volume |
| Operational excellence | Subscriptions and filters are code; adding a country adds its subscription; diagnostics to Log Analytics |
| Performance | Dedicated capacity: no noisy neighbours during the month-end peak |

## Azure Cosmos DB for NoSQL

**Why.** Almost every access is a point read or write by key, with ETag compare-and-set for the ordering rule and per-container TTL for retention (ADR-0002, SOLUTION_DESIGN §7).

**Discarded.**
- **Azure SQL Database:** retention needs purge jobs, scaling is vertical, and the serverless tier's resume delay does not fit the webhook path (ADR-0002).
- **Table Storage:** no TTL and no secondary indexes for the FR7 lookups.
- **Serverless Cosmos DB:** cheapest at low volume, but it has no zone redundancy, and a burst of 40/s with replays is better served by a provisioned ceiling. A reasonable choice for dev (see cost levers).

**SKU.** Provisioned **autoscale**, ceiling 4,000 RU/s per container in test and prod (1,000 in dev), single region. Session consistency. Continuous backup: 30 days in prod, 7 elsewhere.

| Pillar | How |
|---|---|
| Reliability | Zone redundancy in prod; point-in-time restore within the backup window |
| Security | No account keys; private endpoint only; a custom data role with **no delete** (documents only expire through TTL) |
| Cost | Autoscale bills 10% of the ceiling on the 26 normal days and scales only for the month-end peak; no index at all on `payment-state` |
| Operational excellence | Containers, partition keys, indexes and TTL are code; throttling (429) alert |
| Performance | Partitioning by `paymentKey`: point operations of about 1 RU, no hot partitions |

## Azure Key Vault (two vaults)

**Why.** NFR6 requires a secrets store. Two vaults, split by reader: provider webhook secrets (only `payment-intake`) and the Salesforce client secret (only the writers). Key Vault has no per-vault fee, so the split gives least privilege for free.

**Discarded.** App Configuration (not a secrets store) and Container Apps secrets (they live in the app definition, and rotation would need a redeployment).

**SKU.** Standard (software-protected keys are enough for HMAC and client secrets).

| Pillar | How |
|---|---|
| Reliability | Soft delete and purge protection; secrets cached in the apps, so a short Key Vault outage does not stop intake |
| Security | RBAC only (no access policies); private endpoint only; every read audited in Log Analytics; values never in code |
| Cost | Billed per operation; with caching, well under USD 1/month |
| Operational excellence | Rotation with a `-next` secret accepted alongside the current one, with no downtime |
| Performance | In-memory cache in the apps; Key Vault is never on the per-request path |

## Network: VNet, Private Endpoints, Private DNS

**Why.** Nothing in this workload has a public endpoint. The only internet-facing entry is the API Manager, which reaches the VNet privately (assumption 8).

**Discarded.** Service endpoints with firewall rules (the services keep public endpoints, and Service Bus Standard does not support them anyway).

| Pillar | How |
|---|---|
| Reliability | Private DNS zones linked to the VNet; no dependency on public DNS |
| Security | Public network access disabled on Service Bus, Cosmos DB and Key Vault |
| Cost | About USD 7.30 per endpoint per month, plus USD 0.50 per DNS zone |
| Operational excellence | One reusable private-endpoint module for every service |
| Performance | Traffic stays on the Azure backbone |

## Managed identities

**Why.** No credentials anywhere: every app authenticates to Service Bus, Cosmos DB, Key Vault and the registry with its own identity. User-assigned identities exist before the apps, so their roles are in place when the first replica starts. There is one writer identity per country, so a Colombian writer cannot read Mexican messages.

**Discarded.** System-assigned identities (roles would be granted after the app starts, which causes failed first starts) and service principals with secrets (they are secrets).

## Application Insights and Azure Monitor alerts

**Why.** End-to-end tracing by `correlationId`, per-country freshness and DLQ alerts (NFR7). Workspace-based, on the platform Log Analytics workspace, so there is one place to query and one retention policy.

**Discarded.** A dedicated workspace (it would duplicate platform governance) and third-party APM (not part of Meridian's platform).

| Pillar | How |
|---|---|
| Reliability | Alerts per country and platform-level DLQ and quarantine alerts that do not depend on our own telemetry |
| Security | Ingestion with Entra ID only (`DisableLocalAuth`); payer personal data is never sent |
| Cost | Ingestion is the cost driver: sample successful request traces, keep every failure |
| Operational excellence | Two routes: page for payments at risk now, notify for the working day |
| Performance | Freshness p95 is measured and alerted per country |

---

## Monthly cost estimate (order of magnitude)

**Assumptions:**
- Pay-as-you-go **list prices in USD**, single region, no reservations, 730 hours per month. Prices vary by region and change over time; check them in the Azure Pricing Calculator before using these figures.
- Volume from NFR1: 26 normal days at 15,000 notifications and 4 peak days at 120,000 (≈ 870,000 per month).
- Container Apps at the **active** rate for every replica, which overstates cost (idle replicas are billed at a lower rate, and there is a monthly free grant).
- Cosmos DB autoscale at its floor (10% of the ceiling) on normal days and about 1,000 RU/s per container on peak days in prod; zone redundancy adds 25%.
- About 15 GB per month of telemetry in prod (5–8 GB in dev and test) at about USD 2.30/GB.

| Service | dev | test | prod |
|---|---|---|---|
| Service Bus Premium (1 MU) | 680 | 680 | 680 |
| Container Apps | 160 | 160 | 220 |
| Cosmos DB | 25 | 80 | 120 |
| Private endpoints and DNS | 30 | 30 | 30 |
| Monitoring and alerts | 10 | 30 | 50 |
| Key Vault | 1 | 1 | 1 |
| **Total per month** | **≈ 900** | **≈ 980** | **≈ 1,100** |

**All three environments: ≈ USD 3,000 per month.**

**What drives the cost.** Service Bus Premium is **60–75%** of every environment. That is the price of keeping the payment backbone off the public internet with the same topology in every environment. The rest of the design is consumption-priced: Cosmos DB and Container Apps follow the month-end peak instead of being sized for it all month.

**Cost levers, with their trade-offs:**

| Lever | Saving / month | Trade-off |
|---|---|---|
| dev and test share one Premium namespace (entities prefixed per environment) | ≈ 680 | Shared blast radius and quotas between dev and test |
| Service Bus Standard in dev only | ≈ 670 | dev uses a public endpoint, so network issues appear only in test |
| Cosmos DB serverless in dev | ≈ 20 | A different template path for one environment |
| Writers scale to zero in dev outside working hours | ≈ 100 | First messages of the day wait for a cold start |
| 1-year reservations (Cosmos DB, Container Apps) in prod | 15–30% of those lines | Commitment |

My recommendation is the first lever: a shared non-production namespace keeps private networking everywhere and saves about a quarter of the total.

For scale: at about 870,000 notifications per month, prod costs about **USD 0.0013 per notification**, compared with a nightly import that leaves suspended customers waiting up to 48 hours.
