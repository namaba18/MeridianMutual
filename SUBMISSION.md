# Submission

## Candidate

| Field | Value |
|---|---|
| Candidate ID (provided by your recruiter) | namaba93@gmail.com|
| Final commit SHA | |
| Date and time of submission (with time zone) | |

## Time spent

| Part | Approximate time |
|---|---|
| A. Integration and data design | 2 h |
| B. Integration component | 3 h |
| C. Azure IaC and CI/CD | 3 h |
| D. Operations | 1 h |
| E. Code review | 1 h |
| F. Responsible AI | 30 min |
| **Total** | 10 h 30 min|

## Stack

- Practical part (Part B): Java 21 + Spring Boot 3.5.16, Maven Wrapper (Maven 3.9.9), JUnit 5 + Spring MockMvc. 112 tests.
- Infrastructure as code: Bicep 0.49.2, with a strict linter configuration (`infra/bicepconfig.json`).
- Pipeline: GitHub Actions. The CI gates run on every pull request and on `main`. CD (dev → test → prod) uses OpenID Connect to Azure, is defined but never run, and stays switched off until `CD_ENABLED` is set.
- CI run link (optional): https://github.com/namaba18/MeridianMutual/actions/workflows/ci.yml?query=branch%3Amain

## Where to start reading

1. **[`docs/design/SOLUTION_DESIGN.md`](docs/design/SOLUTION_DESIGN.md)**, sections 4 and 5: how a payment is recorded exactly once and never goes back to an older state, and what happens on each kind of failure. The decisions behind it are in [ADR-0001](docs/adr/0001-salesforce-integration.md) (batched upserts to Salesforce) and [ADR-0003](docs/adr/0003-webhook-acknowledgement.md) (`2xx` only after a durable publish).
2. **[`practical/README.md`](practical/README.md)**: how the intake service implements that design, the table that maps each requirement to its tests, and the known gaps. The code starts at `IntakeService`.
3. **[`docs/operations/INC-0412-RESPONSE.md`](docs/operations/INC-0412-RESPONSE.md)**: the design applied to a real incident, including how to replay the dead-lettered messages without reinstating a policy whose payment was reversed.

## Known gaps and what I would do next

In priority order:

1. **`salesforce-writer` and `payment-ops` are designed, not built.** That covers the batching, the ordering rule, the circuit breaker, the API budget guard, the replay tool and the reconciliation. Several alerts in `infra/` depend on metrics these services will emit. I would build `salesforce-writer` first: it is where FR3–FR5 are decided.
2. **Real Azure adapters.** `payment-intake` talks to Service Bus, Cosmos DB and Key Vault through ports with in-memory stubs. The next step is the real adapters, with integration tests against the Service Bus and Cosmos DB emulators. The service must also validate the IdP token that the API Manager sends (SOLUTION_DESIGN §1.2).
3. **Austral (Chile)** is in the design but not in `practical/`, as Part B required.
4. **Nothing has been deployed.** The Bicep files are compiled and linted, but there was no what-if against a real subscription, and the alert queries have not been run against real telemetry. That would be the first job in the test environment.
5. **Migration to Spring Boot 4 before 2027-03-31.** Two vulnerabilities in `spring-webmvc`, which are not exploitable here, are accepted until then in `.trivyignore`. After that date the CI scan fails again.
6. **Observability and load.** JSON logs, OpenTelemetry traces and freshness metrics are designed in `docs/operations/OPERATIONS.md`, but not implemented. The burst of 40 requests per second has not been load tested.
7. **Assumptions to confirm with the stakeholders.** [`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md) lists 14. The most important ones are:
   - Quetzal's refund amount is cumulative.
   - The API Manager forwards the body byte for byte, which the signatures need.
   - The 1-hour freshness target depends on how long Salesforce outages last.

The full list of gaps for the practical part is in [`practical/README.md`](practical/README.md#known-gaps).
