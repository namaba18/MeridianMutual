# API contracts

| Contract | File | Audience |
|---|---|---|
| Provider webhooks (OpenAPI 3.0) | [`webhooks.openapi.yaml`](webhooks.openapi.yaml) | Quetzal Pagos, Andina Pay (and later Austral Pagos) |
| Canonical payment event (JSON Schema 2020-12) | [`events/premium-payment.schema.json`](events/premium-payment.schema.json) | `salesforce-writer`, Finance, Notifications |

Contracts come first. The code is written against them and tested against them (see `practical/`).

## How the contracts evolve without breaking anyone

### 1. Provider webhooks: we do not control the providers

Providers change on their own schedule. Andina API 2.3 changed the format of `referencia` without notice (INC-0412). The contract is therefore **tolerant on input**:

- **Unknown fields are ignored.** Payloads are `additionalProperties: true`, so a new field like Andina's `canal` changes nothing.
- **States are not enums.** An unknown `type` or `estado` is not rejected: it is accepted and quarantined, and an alert fires (SOLUTION_DESIGN §3.4).
- **Provider versions are absorbed by the adapter.** A new provider API version is handled inside that provider's adapter, so the canonical event and the rest of the flow do not change (NFR9).
- **Secret rotation without downtime.** Each adapter accepts the current and the next secret during a rotation window. Quetzal already sends several `v1` signatures for this.
- **Our own side is versioned in the path (`/v1/webhooks/...`).** Changing what we answer (status codes, timeouts, required headers) is a breaking change for a provider: it is published as `/v2/...` and each provider migrates when ready, while `/v1` keeps working.
- **Watching for changes:** the squad follows each provider's changelog, and every new sample or test vector is added to the contract tests.

### 2. Canonical event: we control the producer, the consumers are independent

The version is expressed in two places: the major version in the schema `$id` (`.../premium-payment/v1/...`), and `schemaVersion` (`major.minor`) in every event. It is also copied to a message property so consumers can filter without reading the body.

| Change | Kind | How it is released |
|---|---|---|
| Add an optional field | Minor (`1.0` → `1.1`) | Published directly. Old consumers ignore it. |
| Add a value to `country`, `provider` or `currency` (a new country) | Minor | Announced in advance. Consumers must treat unknown values as "not supported yet": skip and alert, never crash. |
| Remove or rename a field, change a type or its meaning, make a field required, change the `eventId` format | **Major** (`v2`) | New schema with a new `$id`. v1 and v2 are published **in parallel** for an agreed period (for example 90 days), until every consumer has migrated. |

**Producer strict, consumers tolerant:**
- **`payment-intake` validates every event against the schema before publishing.** The schema is closed (`additionalProperties: false`), which is the last guard against payer personal data reaching the topic. An event that fails validation is never published: it goes to quarantine with reason `INVALID_PAYLOAD`.
- **What the schema cannot check** is left to the adapter's tests: for example, that the amount inside a refund `eventId` equals `amountRefunded`.
- **Consumers are tolerant readers.** They ignore unknown fields and use only what they need. They must not reject an event for having a field they do not know.

**Message properties**, so routing and filtering never need to parse the body:

| Property | Value | Used for |
|---|---|---|
| `messageId` | `eventId` (replays: `<eventId>:replay:<n>`) | Duplicate detection |
| `sessionId` | `paymentKey` | One payment at a time in `salesforce-writer` (§4.4) |
| `correlationId` | `correlationId` | End-to-end tracing |
| `schemaVersion`, `country`, `provider`, `status` | From the event | Subscription filters (for example `sf-writer-co`) and version routing |

### 3. Governance

- Contract changes are made in this repository by pull request. Consumer teams are code owners of the event schema and must approve changes to it.
- CI lints the OpenAPI contract and compares it with `main`: a breaking change fails the build (oasdiff, Part C). The event schema is compiled in CI, and every change to it needs the consumer teams' approval as code owners.
- Every provider sample in `reference/providers/` is a contract test: the adapter must accept it and produce an event that is valid against the schema.
