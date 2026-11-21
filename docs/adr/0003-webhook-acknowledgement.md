# ADR-0003: Webhook acknowledgement — answer 2xx only after a durable publish

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

A notification is **accepted** when Meridian returns a `2xx` to the provider, and **no accepted notification may be lost** (NFR3). After a `2xx`, providers never send it again, so from that moment the notification exists only on our side.

The moment we answer must satisfy two opposing forces:

- **Answer late enough** that the notification is already safe, so it survives a crash, a restart or a deploy.
- **Answer early enough** to fit the provider timeout (NFR4): **5 s for Andina**, 10 s for Quetzal and Austral. Otherwise the provider retries, which creates duplicates, and Andina gives up after 5 attempts in 24 h.

ADR-0001 already removes Salesforce from the critical path, so the decision here is **what must be durable before we answer `2xx`**.

The squad's prototype (PR-214) answers `200` and then processes the notification in an in-memory thread pool. This ADR decides why that is not acceptable and what replaces it.

## Options considered

### Option 1: Answer at once, process in memory afterwards (PR-214)
- Pros:
  - Fastest possible answer.
  - The simplest code.
- Cons:
  - **Data loss.** If the pod restarts, scales in, is redeployed or crashes after the `200`, the notification is gone. The provider will never resend it.
  - Errors in the background thread are invisible to the provider and, in PR-214, swallowed.
  - The in-memory queue has no limit, so a burst can exhaust the memory.

### Option 2: Answer after a durable publish to Service Bus
The order is: verify the signature, map to the canonical event, check duplicates, **publish to Service Bus**, save the status, and only then answer `2xx`.
- Pros:
  - Once Service Bus acknowledges the send, the message is replicated and durable. No accepted notification can be lost.
  - Fast: tens of milliseconds for the publish, well within 5 s.
  - If anything fails before the publish, we answer `503` and the provider keeps the notification and retries. Nothing is lost on either side.
- Cons:
  - Service Bus is on the critical path of every webhook. While it is unavailable we answer `503` and rely on provider retries.
  - A crash between the publish and the `2xx` makes the provider retry an event already published. It must be deduplicated (SOLUTION_DESIGN §4.2).

### Option 3: Transactional outbox (write to Cosmos DB, publish from the change feed)
The order is: verify, map, **write one document to Cosmos DB**, and answer `2xx`. A separate process reads the Cosmos DB change feed and publishes to Service Bus.
- Pros:
  - A single durable write before answering.
  - A Service Bus outage does not reject notifications.
- Cons:
  - One more component, the change-feed processor, with its own failure modes and lag.
  - It moves the critical dependency from Service Bus to Cosmos DB. It does not remove it.
  - Adds latency before the event reaches Salesforce and the downstream consumers.

### Option 4: The API Manager writes the raw request straight to a queue
The order is: the API Manager enqueues the request and answers `2xx`, and the signature is verified later by a consumer.
- Pros:
  - Fastest acknowledgement, with no custom code on the hot path.
- Cons:
  - We would answer `2xx` to **forged or invalid requests**. Anyone on the internet could fill the queue.
  - Raw payloads with **payer personal data** would sit in the queue (NFR6).
  - A signature failure can no longer be reported to the provider.

## Decision

**Option 2: answer `2xx` only after the canonical event is durably published to Service Bus.**

The intake sequence and its time budget, against Andina's 5 s:

| Step | Typical time | On failure |
|---|---|---|
| 1. Verify signature and timestamp | < 5 ms | `401`, nothing stored |
| 2. Map to the canonical event and validate it against the schema | < 5 ms | Unknown or missing state, unsupported provider version, unreadable payload, policy reference that cannot be normalized, or an event that breaks the schema: quarantine, `200` (SOLUTION_DESIGN §3.4, §3.5). If the quarantine queue is unavailable: `503`, the provider retries. |
| 3. Look up `eventId` in Cosmos DB | ≈ 10 ms | Duplicate: `200`, nothing published |
| 4. **Publish to Service Bus** (`messageId = eventId`) | ≈ 20–50 ms | `503`, the provider retries |
| 5. Save status `RECEIVED` in Cosmos DB | ≈ 10 ms | Logged and still answered `200`: the event is already durable, and `salesforce-writer` creates the missing status document |
| 6. Answer `200` | — | — |

- **Internal deadline of 3 s.** If the steps are not done by then, we answer `503` before the provider times out. If the publish had in fact succeeded, the retry is caught by Service Bus duplicate detection.
- **Graceful degradation.** If Cosmos DB is unavailable, step 3 is skipped and we still publish. Duplicates are then caught by Service Bus duplicate detection and by the ordering rule in `salesforce-writer`. Only a **Service Bus** outage makes us answer `503`.

## Consequences

- **Positive:**
  - NFR3 holds by construction: every `2xx` corresponds to a message stored in Service Bus.
  - Answers take tens of milliseconds, far from the 5 s limit, even at 40/s.
  - Redeploys, scale-in and crashes never lose a notification. At worst the provider retries one, and the retry is deduplicated.
  - Signature failures are reported to the provider with a `401`, and forged requests never enter the system.
- **Negative:**
  - The availability of intake depends on Service Bus. During a Service Bus outage, every provider receives `503` and our durability depends on their retry policies: Andina retries only 5 times in 24 h.
  - Each webhook makes 2 to 3 calls to Azure services before answering, which is more code and more latency than option 1.
  - There is a window (step 4 succeeded, the `2xx` was lost) in which the provider retries an event we already have. Deduplication is mandatory, not optional.
- **Risks and how they are mitigated:**

| Risk | Mitigation |
|---|---|
| A Service Bus outage longer than Andina's retry window | Service Bus Premium is zone-redundant, with an SLA of 99.9%. Alert on any `503` rate above normal. If an outage happens anyway, Andina's 24 h of retries is the safety net, and the provider's dashboard is the reconciliation source afterwards. |
| Slow Azure calls push the answer over 5 s | Internal deadline of 3 s, client timeouts on every call, an alert on p95 intake latency, and minimum replicas so there is no cold start. |
| Someone re-introduces "answer first, process later" | This ADR, a code-review checklist item, and the tests that check no `2xx` is returned before the publish (`IntakeServiceTest$Durability`). An integration test that kills the service after the `2xx` and checks the message is in the queue is planned with the real Azure adapters (`practical/README.md`, known gaps). |
