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
- **Vulnerabilities:** upgrade to Spring Boot 3.5.16 and accept one remaining CVE with an expiry date, instead of migrating to Spring Boot 4 now.
- **The tone of the PR-214 review:** I rewrote the greeting and the paragraph on the suggested approach, and refocused "What looks good" on the author's work.

## Validation

Most checks were run by the assistant at my request. I read the results, and the CI results on GitHub, before accepting each part.

| Output | How it was checked |
|---|---|
| Signature verification and mapping | All 7 official test vectors from the provider specs, plus negative cases. 107 tests with `./mvnw verify`, 5 consecutive green runs locally, green on GitHub Actions. |
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

## Data I shared with AI tools

- The contents of this repository: the assessment's fictional scenario, reference files and samples, and my own work.
- The **fictional sandbox secrets** published in the provider specs. They are used only in tests.
- Screenshots and logs of my GitHub Actions runs and my editor.
- My own account details, as far as the work needed them: my GitHub username, my git author settings, and my local user name in file paths.

**No real secrets, credentials or personal data were shared.** No code or data from any employer was shared. The names and identifiers in the samples are fictional.
