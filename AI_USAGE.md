# AI usage disclosure

- [ ] I did not use any AI tool in this assessment.

I used one AI assistant for most of the work. I directed it one section at a time, made the design decisions, reviewed every section before it was committed, and ran or checked the validations below. Every commit made with its help carries a `Co-Authored-By: Claude` trailer. That is all of them, except the template's initial commit and a Dependabot update with its merge. That is why Claude appears as a contributor on GitHub: I kept it on purpose, as part of this disclosure.

## Tools

| Tool (name and version, if known) | Where you used it (part and files) | What you used it for |
|---|---|---|
| Claude Code (Anthropic), desktop app, model Claude Opus 5.5 | All parts: `docs/`, `practical/`, `infra/`, `.github/`, `pr-review/REVIEW.md`, this file | Explaining the assessment; drafting design documents, ADRs, contracts, Java code and tests, Bicep, workflows, operations documents and the review; running builds, tests, linters and scanners locally; reviewing its own drafts against the reference files |

The assistant worked in this repository, on my machine, under Claude Code's permission controls. It ran Maven, Bicep, Docker, git and the linters. With my approval, it also installed the JDK and the Bicep CLI. It had no GitHub credentials: I pushed, merged and closed pull requests myself.

## What I decided

The assistant proposed options; these choices were mine:

- **Stack:** Java 21 with Spring Boot, Cosmos DB as the data store (ADR-0002), and Bicep for the infrastructure.
- **Design:** an unknown provider state goes to quarantine, never to a default status. The context diagram is simpler than the first draft.
- **Vulnerabilities:** upgrade to Spring Boot 3.5.16 instead of migrating to Spring Boot 4 now. The CVEs that are fixed only in Spring Framework 7 are accepted with an expiry date: one after the upgrade, and a second one reported by the next scan.
- **The tone of the PR-214 review:** I rewrote the greeting and the paragraph on the suggested approach, and refocused "What looks good" on the author's work.

## Validation

Most checks were run by the assistant at my request. I read the results, and the CI results on GitHub, before accepting each part.

| Output | How it was checked |
|---|---|
| Signature verification and mapping | All 7 official test vectors from the provider specs, plus negative cases. 107 tests with `./mvnw verify`, 5 consecutive green runs locally, green on GitHub Actions. The final review round added 5 tests (112). |
| Canonical event and contracts | Every official sample maps to an event that validates against the published JSON Schema (in the tests). Spectral lint on the OpenAPI file. Ajv strict compile of the schema. |
| Container image | Built and run locally: health `UP`, runs as a non-root user, an unsigned webhook gets `401`. Trivy, CodeQL and Gitleaks in CI. |
| Infrastructure and pipelines | `bicep build`, strict `bicep lint` and `build-params` for dev, test and prod. `actionlint` on the workflows. I checked that every action version tag exists. CD is defined but not run, as the assessment requires. |
| Secret scanning | Gitleaks over the whole history. A control test with a fake live-looking secret confirmed that the allowlist does not hide real secrets. |
| Numbers in the documents | Recomputed the capacity, the API budget, the error budgets and the INC-0412 figures against `SCENARIO.md` and `reference/`. |
| PR-214 review | The line numbers were recomputed from the diff with a script, and each finding was checked against the code and the Austral spec. |
| Every document | A dedicated review pass, at my request, before each commit ("review before"), comparing the draft with the code, the contracts, the IaC and the reference files. |

## What I discarded or corrected

These errors in the assistant's output were found by the review passes, by CI, or by me. Each one was corrected:

- **The first context diagram was too detailed** to be useful. I asked for a simpler one.
- **The event schema contradicted the mapping.** It required a normalized policy number, while the mapper published references it could not normalize. Those now go to quarantine.
- **The webhook contract had a strict `valor` pattern.** It could make the gateway reject genuine Andina notifications, so it was removed.
- **Bicep autoscaled the Salesforce writers with KEDA.** That broke the agreed limit on concurrent Salesforce requests. They now run with fixed replicas.
- **The first image scan found 40 HIGH/CRITICAL vulnerabilities in Spring Boot 3.3.5**, the version the assistant had chosen. I chose the upgrade path. Two findings were accepted only after checking that the code has no views and no streaming responses.
- **The first Dependabot configuration opened major-version upgrades.** It was made conservative: minor and patch only, grouped. I merged the GitHub Actions group (#11). I closed the Maven group (#12), and its one useful bump was applied by hand.
- **A platform alert claimed to cover the quarantine queue**, but its metric only counts dead-lettered messages. I added the missing alerts.
- **`OPERATIONS.md` said a `401` returns `X-Correlation-Id`.** The contract and the code do not. Corrected.
- **The INC-0412 draft claimed every DLQ message failed with `INVALID_FIELD`**, although the incident shows only three samples. That is now a confirmation step, together with a window mismatch in the data (24 h vs. 32 h).
- **The PR-214 draft said the second JPA `save` fails on the primary key.** Spring Data merges an entity with an assigned id, so it usually updates the row silently. Corrected, and one missing finding was added.
- **Inconsistent CVE counts and a stale library version in the READMEs.** Corrected.
- **The assistant once committed without waiting for my approval.** It told me, and I kept the commit. From then on, it committed only when I asked.

### Final review round

Before submitting, I went through each part again, asking about anything that was not clear to me. Changes from that round:

- **Context diagram legend** (`SOLUTION_DESIGN.md` §1). It said only "Numbers follow one notification", which did not make clear that the numbers label the arrows. It now explains that arrows 1–5 are the main path, and arrows without a number are side flows.
- **Four statements that no longer matched the work**, found while reading every document again:
  - **The vulnerability decision**, in this file, said one CVE was accepted. Two are accepted, as `.trivyignore` and `practical/README.md` show.
  - **ADR-0002** listed KEDA queue-length scaling as an advantage of Container Apps, although the writers had been moved to fixed replicas.
  - **`practical/README.md`** (gap 4 at the time, now gap 5) said a contract linter still had to be added to CI. Spectral and oasdiff were already there; only the conformance test is missing.
  - **`.github/workflows/README.md`** said Dependabot runs weekly. It runs monthly, since it was made conservative.
- **Operations trust boundary** (`SOLUTION_DESIGN.md` §1 and §1.2). The table listed it, but the diagram drew Operations outside any dashed box. Operations now sits in a "Meridian corporate network" boundary. The table row was renamed to show that operations reaches `payment-ops` through the API Manager's internal gateway, as the arrows do.
- **Unknown or missing provider state** (`SOLUTION_DESIGN.md` §2, §2.1 and §3.4).
  - The sequence diagram had no quarantine branch. It now shows that a notification that cannot be mapped goes to `payments-quarantine` with `200`.
  - §3.4 said a missing state is quarantined with reason `UNKNOWN_PROVIDER_STATUS`. The code uses `INVALID_PAYLOAD`, and I kept the code: an unknown state means the adapter needs a new rule, while a missing state means the provider sent a broken notification, and operations handles each one differently. The document now says so.
  - No test covered a missing state. I added one per provider (109 tests, green locally). ADR-0003, `practical/README.md` and the test count in the other documents were updated.
- **Quarantine queue in the context diagram** (`SOLUTION_DESIGN.md` §1 and §1.1). `payments-quarantine` was missing from the diagram and from the components table. It is now drawn as its own queue: `payment-intake` writes to it, and `payment-ops` reviews and replays from it.
- **An unstated assumption about provider resends.** The new text in §3.4, and gap 7 in `practical/README.md` (gap 6 at the time), said that operations asks the provider to resend a notification with a missing state or an unreadable body. None of the provider specs mentions manual resends. It is now assumption 13 in `docs/ASSUMPTIONS.md`, with a manual fallback if a provider cannot resend, and both places refer to it.
- **Provider API versions** (`docs/api/README.md` and `SOLUTION_DESIGN.md` §3.4). The README said a new provider version is "absorbed by the adapter", but nothing did it: Quetzal sends `api_version` in every event and the mapper ignored it. A new version that changed what a field means, such as the unit of `amount`, would have passed the schema and written a wrong amount to Salesforce. The Quetzal adapter now maps only `2025-06-01` and quarantines any other version, or none, with a new reason, `UNSUPPORTED_PROVIDER_VERSION`. I added 2 tests (111, green locally). Andina sends no version, so the documents now say its adapter accepts the old and the new format at the same time. The new behaviour relies on assumption 14. The OpenAPI contract, ADR-0003, `OPERATIONS.md`, `practical/README.md` and the test counts were updated.
- **The sequence diagram in `practical/README.md`** did not match `IntakeService`. It drew schema validation as a step with no outcome, and quarantine as always answering `200`. In the code, an event that breaks the schema is quarantined too, and if the quarantine queue is down the answer is `503`, so the provider retries and nothing is lost (NFR3). The diagram now shows both, and the NFR3 row of the test table mentions the quarantine case, which `IntakeServiceTest` already covered.
- **A last consistency pass over Parts A and B**, comparing the design, the ADRs and the code. Seven fixes:
  - **FR7 for quarantined notifications** (`SOLUTION_DESIGN.md` §5.2, §7.2, §8.1). The design said their status in Cosmos DB becomes `DEAD_LETTERED`, but `payment-intake` writes no document for them: they have no `eventId` yet. The design now says `payment-ops` finds them by peeking the quarantine queue, which the infrastructure already allows, and the Ops API has a lookup for it.
  - **The API Manager request id** (`OPERATIONS.md`, assumption 8). The documents said `payment-intake` logs `X-Request-Id` next to the `correlationId`, but the code did not read it. It now logs it, only if it looks like an id, so it cannot forge log lines. I added 1 test (112, green locally).
  - **The API Manager token** (`SOLUTION_DESIGN.md` §1.2). The design listed a machine-to-machine token from the IdP, but neither the code nor the infrastructure validates it. §1.2 now says where it will be validated, and it is gap 2 in `practical/README.md`.
  - **ADR-0002** named a `ProviderAdapter` interface that does not exist. It now names `SignatureVerifier` and `PayloadMapper`.
  - **The quarantine paths in ADR-0003 and in the §2 sequence diagram** now include a bad policy reference, an event that breaks the schema, and `503` when the quarantine queue is unavailable.
  - **ADR-0003** described a "kill after `2xx`" integration test as if it existed. It is now planned work, in gap 1 of `practical/README.md`.
  - **Austral signs no timestamp**, and the design did not say what protects against a replayed request. §1.2 now explains that it is answered as a duplicate of the original `eventId`.

  The same pass found that `SUBMISSION.md` still said `docs/ASSUMPTIONS.md` lists 12 assumptions. It lists 14.
- **Two statements in Part C** (`infra/README.md`, `docs/infra/AZURE_SERVICES.md`), found while going through the infrastructure again:
  - The least-privilege table said `payment-ops` reads the DLQs. It also reads the quarantine queue, which the Bicep already allows and FR7 now relies on.
  - The documents said nothing in the workload has a public endpoint, but Application Insights receives telemetry on its public endpoint. The risk is low, because only an Entra ID identity can send data and payer data is never sent. It is now stated in the documents and in the Bicep comments, and listed as a known gap for the platform team, since a private endpoint needs an Azure Monitor Private Link Scope over the shared workspace. `bicep build` and `bicep lint` still pass.
- **A consistency pass over Parts C and D.** Part D matched the incident and the design, and its figures were recomputed. Part C had seven issues:
  - **The CD deletion guard could never fire.** It looked for resources marked `Delete` in the what-if, which only happens in complete mode, and the pipeline deploys in incremental mode. A change made by hand shows up as a property deletion instead, so the guard now looks for both and lists the resources. A query test with sample what-if output (a hand-added IP restriction, a clean plan and a deleted resource) gave the expected result in each case, and actionlint passes on the three workflows. The README now says the guard has not run against real what-if output, which can need a list of noisy properties.
  - **The prod plan identity could not run the full what-if**: the template also grants `AcrPull` in the shared registry's resource group, where that identity had no rights. The CD README now gives it read and what-if rights there.
  - **A rollback redeploys the old image with the current infrastructure.** The README said an environment is always described by one commit; it now says so, and that reverting the commit rolls back both.
  - **Two CI tools ran their latest version**: oasdiff (`latest` was published the day of this review) and the Bicep CLI. Both are now pinned (oasdiff v1.35.0, Bicep 0.49.2), like every other tool.
  - **Trace sampling** said "sample successful traces" in `AZURE_SERVICES.md` and "100%" in `OPERATIONS.md`. Both now say 100%, which the cost estimate already covers.
  - **The quarantine alert description** listed only three of the quarantine reasons.
  - **Managed identities** had no Well-Architected table in `AZURE_SERVICES.md`, unlike every other service.

## Data I shared with AI tools

- The contents of this repository: the assessment's fictional scenario, reference files and samples, and my own work.
- The **fictional sandbox secrets** published in the provider specs. They are used only in tests.
- Screenshots and logs of my GitHub Actions runs and my editor.
- My own account details, as far as the work needed them: my GitHub username, my git author settings, and my local user name in file paths.

**No real secrets, credentials or personal data were shared.** No code or data from any employer was shared. The names and identifiers in the samples are fictional.
