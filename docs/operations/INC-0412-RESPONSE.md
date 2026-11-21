# INC-0412 — Response

On-call response to [`INC-0412`](../../reference/incident/INC-0412.md): Colombian customers have paid policies that are still suspended. Times are Colombia time (UTC−5). The incident is on **v1**. The last section shows where the target design already prevents each failure.

## 1. First 30 minutes (08:15–08:45)

| When | What I check or do | Why |
|---|---|---|
| 08:15 | Acknowledge, open the incident channel, tell the contact-center lead I own it. **Propose SEV-1**: paying customers are without coverage, the count is growing, and today is a month-end peak day. | Customers are exposed now. The severity decides who joins and how often we report. |
| 08:18 | **Scope.** Only Colombia is affected. Volume is normal (37,215 vs. 36,480) and there are no signature failures, so intake works. Salesforce `5xx` are low (7), so Salesforce is up. | Rule out the big causes first: traffic, security, an outage. |
| 08:21 | **Find the failing step.** The DLQ holds 4,312 messages, and 4,312 × 10 deliveries = **43,120**, exactly the count of `400`. | Every message failed 10 times on a data error, which no retry can fix. |
| 08:24 | **Read DLQ messages.** The three samples fail with the same error: `INVALID_FIELD`, policy not found. `referencia` is `000215560` or `pol-co-000198004`, not `POL-CO-<9 digits>`. | The error names the field; the values show the cause. |
| 08:28 | **Match the timeline with the changes.** The first dead letter is at 23:41 Saturday. Andina **API 2.3** went live at 23:00 that night and sends `referencia` as the payer typed it. | The first failure follows a known change. |
| 08:32 | **Check the API budget:** 66% vs. 38% normally. The 43,120 extra calls are 29% of 150,000, which is the whole gap. Today is a peak day, so the useless retries grow with the traffic. The Salesforce team may suspend an integration that exceeds its budget, and the org-wide limit is shared. I warn the Salesforce team. | A second incident, for all countries, could start here. |
| 08:36 | **Contain.** Nobody resubmits the DLQ from the queue explorer, because each message would fail 10 more times. I add DLQ alerts for `payments-co` and `payments-cl`, and I start the hotfix (section 3). | Stop the damage from growing before repairing it. |
| 08:40 | **Give the contact center a workaround.** I export the affected transactions from the DLQ, through a restricted channel, so agents can confirm payments and the business can decide on manual reinstatement. | 37 complaints are the visible part. 4,198 approvals are not recorded, nor are 61 reversals, which can leave a policy active after a refund. |
| 08:45 | First status update: impact, likely cause, next steps. The next update is at 09:15. | One source of truth for stakeholders. |

## 2. Most likely root cause

**Andina API 2.3 stopped normalizing `referencia`. v1 uses it unchanged as the policy external id, so Salesforce cannot find the policy (`400 INVALID_FIELD`). v1 then retries each failure 10 times in about 2 seconds, and dead-letters it into a DLQ that has no alert.**

**Evidence:**

- **The timing matches.** The change took effect at 23:00 and the first message was dead-lettered at 23:41. Nothing was dead-lettered before.
- **Only Colombia is affected**, although Mexico and Chile write to the same org.
- **The failure is partial.** A Colombian payment was recorded at 23:52. That is consistent with payers who type the full number still succeeding.
- **The counts close.** 4,312 × 10 = 43,120 `400` responses, which explain the budget rise. Received minus recorded in Colombia (4,335) is close to the DLQ count.

**Ruled out:**

- **The Salesforce release at 22:00.** Its rule applies when a `Policy__c` is cancelled. The integration never writes `Policy__c`, and reinstatement sets it to *Active*, not *Cancelled*. If it were the cause, the error would be `FIELD_CUSTOM_VALIDATION_EXCEPTION`, in every country, from 22:00.
- **Our deploy `v1.14.0`.** Nothing was dead-lettered for 29 hours after it. It only explains why `referencia` is no longer in the logs.

**Confirm it within the first hour:**

1. Group the 4,312 messages by `lastError` and by the **shape** of `referencia`. Every one should be `INVALID_FIELD` on `Policy_Number__c` with a non-canonical shape, while messages recorded since 23:00 match the canonical format. Also check that the `400` count covers the same period as the DLQ: the snapshot says 24 h, but the DLQ spans 32 h.
2. For about 50 of them, check that the normalized number (`POL-CO-000215560`) exists in Salesforce (assumption 9).
3. Ask Andina for the exact time 2.3 went live.

## 3. Remediation

**A. Stop the bleeding.** The hotfix `v1.14.1` goes through the normal pipeline, with an expedited approval.

1. **Normalize in the Andina mapper**, with the rule of SOLUTION_DESIGN §3.5:
   - trim and upper-case;
   - `POL-CO-` + 9 digits is kept;
   - 9 digits become `POL-CO-` + digits;
   - anything else goes to a review queue with an alert, never to Salesforce.

   The replay tool (B) uses the same function.
2. **The consumer stops retrying `400` data errors** (`INVALID_FIELD`, `FIELD_CUSTOM_VALIDATION_EXCEPTION`, `REQUIRED_FIELD_MISSING`) and dead-letters them at once, with the reason. `UNABLE_TO_LOCK_ROW` is also a `400`, but it stays retryable, with backoff. This change alone would have saved about 38,800 calls.
3. **Tests** for the incident's shapes. Then deploy and confirm that live Colombian traffic produces no new dead letters for 30 minutes.

**B. Reprocess the DLQ safely**, only after A is verified; otherwise the DLQ fills up again.

1. **Snapshot.** Peek (do not receive) and export the DLQ for audit. v1 messages may carry payer data, so the export goes to a restricted, encrypted location and is deleted when the incident closes.
2. **Classify.** References that can be normalized (nearly all) are replayed. The rest go to manual resolution. Andina's retries carry a new `notificacion_id`, so duplicates are grouped by `transaccion.id + estado`.
3. **Respect the order.** v1 has no ordering guard. `TRX-CO-5603318` has an `APROBADA` (Saturday) and a `REVERSADA` (Sunday) in the DLQ. If the `APROBADA` is replayed last, Salesforce shows *Captured* and reinstates a policy whose money was returned. So, for each transaction, take the **highest state** in the DLQ (`PENDIENTE` 1, `APROBADA`/`RECHAZADA` 2, `REVERSADA` 3), compare it with what Salesforce already has (read in bulk with SOQL, a few dozen calls), and write it only if it is newer. `TRX-CO-5603318` goes straight to *Refunded*. A transaction with both `APROBADA` and `RECHAZADA` goes to manual review.
4. **Dry run.** A report counts the writes, the stale and duplicate skips, and the manual cases. A second engineer reviews it and the Salesforce team signs it off, because each write can reinstate or suspend a policy.
5. **Pilot.** First the 37 reported cases plus about 20 more. Check that each payment is *Captured* and each policy is reinstated by the flow.
6. **The rest, rate-limited.** Use a replay tool, never the queue explorer. It resubmits the selected, normalized events to `payments-co` at about 5 per second. About 4,250 writes plus the reads stay under 3% of the budget, and the rate spares the reinstatement flow.
7. **Verify, then purge.** Every transaction has the expected status, and only the manual cases remain. If Andina provides a transaction report for 28–30 November, reconcile against it. Only then purge the DLQ.

**C. Customers.** The flow notifies each reinstated customer. The business decides how to treat the gap in coverage, for example a claim during those days. My recommendation: count the payment date, not the day Salesforce recorded it.

## 4. Prevention

| What failed | What prevents it | Where in the target design |
|---|---|---|
| The provider's announced change went unnoticed | One owner per provider reads its changelog; changes are tested in the provider's sandbox before the effective date | Process |
| The reference was passed through unvalidated | Normalize in the adapter; quarantine what cannot be normalized | SOLUTION_DESIGN §3.5 |
| 10 immediate retries of a permanent error | Retry only `5xx`, timeouts and locks, with backoff; data errors go to the DLQ at once | SOLUTION_DESIGN §5.1 |
| A DLQ alert for Mexico only | Alerts generated per country from configuration, plus a platform alert on every DLQ | OPERATIONS §3 |
| 32 hours without a signal | Freshness and completeness SLOs per country; alert on the API budget at 60% | OPERATIONS §1, §3 |
| Hours to find the affected payments | Payment Ops API by provider id and status; replay with dry run, ordering rule and audit | SOLUTION_DESIGN §5.3, §8 |
| No payloads in the logs, so no way to diagnose | Log the shape of a field (`DIGITS_9`), never its value | OPERATIONS §2.3 |
| Format variants not tested | Contract tests that include this incident's variants | `AndinaPayloadMapperTest` |

A blameless post-incident review follows within five working days, with an owner and a date for each action above.
