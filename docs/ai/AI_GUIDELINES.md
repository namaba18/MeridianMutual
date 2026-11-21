# AI guidelines — Payments Integration squad

AI assistants make us faster, but they do not change who is responsible. **The engineer who opens a pull request is its author**, and must be able to explain every line, whoever or whatever typed it. These guidelines apply to coding assistants, chat tools and agents, and to any of our services that calls a language model.

## 1. What is allowed

| Allowed | Not allowed |
|---|---|
| Tools approved by the company, used with company accounts. Approval means the provider does not train on our data, retention is agreed and the hosting region complies with each country's law. | Personal accounts, or tools that are not approved, for any company code or data |
| Drafting code, tests, documentation, ADRs and queries; explaining code; reviewing a diff before a human review | AI as the only reviewer, or AI approving or merging a pull request |
| Agents that run commands in a local or sandboxed workspace, with confirmation for every command that writes, pushes or deploys | Agents with access to production, production credentials, or the CI/CD deployment identities |
| Working with the fictional samples in `reference/` or with synthetic data | Copying a real notification, record or log "just to show the format" |

### Data that must never be shared with an AI tool

1. **Payer personal data**: names, national ids (RUT, cédula, CURP/RFC), emails, phone numbers, addresses (NFR6). Mexico, Colombia and Chile each regulate it.
2. **Real payment notifications, Salesforce records and unsanitized production logs**, even one. They carry payer data and policy numbers.
3. **Secrets**: webhook secrets, Salesforce and IdP credentials or tokens, Key Vault values, connection strings, private keys, `.env` files. If one is pasted by mistake, treat it as leaked and rotate it.
4. **Confidential third-party material**: provider contracts, unpublished security findings, and other companies' code.

Agents that read the repository must exclude local secret files. They run without cloud credentials in their environment.

## 2. Validating AI-generated code and tests before merge

AI-generated code goes through the **same gates as any code**: build, tests, CodeQL, secret scan, dependency review, image scan and human review. On top of that:

- **The author reads every line**, and checks every claimed fact against its source: a provider spec, `API_CONSTRAINTS.md`, the SDK documentation. Assistants state plausible facts with confidence.
- **Tests must fail when the code is wrong.** Break the code on purpose and confirm that a test goes red. An assertion that cannot fail is not a test: PR-214 had `assertNotNull(service)`, and `atMost(1)` where it needed `never()`.
- **Test against the real contract.** Use the providers' official samples and signature test vectors, and the canonical event JSON Schema. Mocks only stand in for the systems around the code.
- **High-risk code needs a second human reviewer.** That covers signature verification, secrets, logging of anything near payer data, money, idempotency and ordering, and the code that writes to Salesforce.
- **Every new dependency must exist and be intended.** Check its name, publisher, licence and version, because assistants can invent package names. Pin the version and let dependency review and the image scan run.
- **Generated infrastructure and pipelines** are checked with the strict `bicep lint` (already a CI gate) and with `actionlint`. Their what-if is reviewed before any deployment.

## 3. Disclosing AI use in pull requests

We add an **"AI assistance"** section to the squad's pull request template:

- **Tool:** for example Claude Code or GitHub Copilot.
- **What it produced:** which files or parts.
- **How you validated it:** tests run, sources checked, cases tried by hand.

Substantial generated content is also marked in the commit with a `Co-Authored-By:` or `Assisted-by:` trailer. Disclosure is not about blame: it tells the reviewer where to look harder. "Tests generated with Copilot, all green" is a disclosure, not a validation.

## 4. When one of our services calls an LLM

Example: operations asks for a feature that **summarizes the dead-lettered payment notifications** of a country, such as "4,312 Colombian notifications dead-lettered since Saturday, all with `INVALID_FIELD` on the policy number". These are the minimum controls before it reaches production:

| Control | Requirement |
|---|---|
| **Approved provider and network** | An enterprise model endpoint under a data processing agreement: no training on our data, agreed retention, a compliant region. It is reached over private networking with a managed identity, so there is no API key in code or configuration. |
| **Data minimization** | Send only allowlisted fields: reason and error codes, counts, provider ids, timestamps, status. Never a payload, payer data or a policy number. A filter enforces the allowlist in code, and a test proves it. |
| **Advisory only** | The model has no tools and no write access. A summary can never trigger a replay, a Salesforce write or a status change. Those stay with a person who holds the `payments-ops-replay` role, through the audited replay flow. |
| **Untrusted input** | Notification fields come from outside (a provider, or a payer typing a reference). Text inside them can try to give the model instructions (prompt injection). It is passed as data, separated from the instructions, and the model's output is shown as plain, escaped text. |
| **Facts computed by code** | Counts, time windows and the list of affected payments are computed deterministically and linked to the Payment Ops API. The model only writes the explanation around them. Every summary is labelled "AI-generated" and links to the underlying messages. |
| **Structured output** | The model answers in a JSON schema (`reasonCode`, `count`, `firstSeen`, `suggestedCheck`), which is validated before display. An invalid answer is dropped, not repaired. |
| **Evaluation** | Before launch, and on every model or prompt change: a fixed set of DLQ scenarios with expected summaries, INC-0412 included. The model version is pinned. |
| **Access and audit** | The same roles as the Payment Ops API (`payments-ops-read`). Each request records who asked, what was sent (field names, not values) and the model version. |
| **Cost and failure** | A timeout, a token budget per request and a daily cap. If the model is unavailable, the page shows the deterministic counts without the summary. A feature flag can switch it off at once. |
| **Privacy review** | Reviewed against each country's data protection law before launch, and again when the data sent or the provider changes. |
