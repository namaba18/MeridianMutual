# Assumptions

> The scenario is intentionally incomplete. Record every assumption you needed to make, and the part it affects.

| # | Assumption | Why I needed it | Part(s) affected |
|---|---|---|---|
| 1 | Quetzal's `amount_refunded` is the **cumulative** amount refunded on the charge, not the amount of the latest refund. | The spec allows several partial refunds per charge, but does not say whether the amount is cumulative. The refund idempotency key and the ordering of refunds depend on it. | A, B |
| 2 | Andina `REVERSADA` and Austral `PAYMENT_REVERSED` always reverse the **full** amount. | Neither spec mentions partial reversals or carries a refunded amount. | A, B |
| 3 | Until Salesforce adds a refunded-amount field, any refund, partial or full, is written as `Status__c = Refunded`. Raised as an open question to the Salesforce team. | `Premium_Payment__c` has no field for the refunded amount, and `Status__c` has no "partially refunded" value. | A |
| 4 | Notifications are spread evenly through the day, except for the 15-minute burst described in NFR1. | Needed to estimate how many batches per day contain at least one record. The upper bound of 25,920 calls per day does not depend on it. | A |
| 5 | The distribution of Salesforce outage lengths is unknown. The worst case (all outages lasting 90 minutes) is used for the freshness analysis. | `API_CONSTRAINTS.md` only gives the range of 5 to 90 minutes. Whether the 99.5%-within-1-hour target is met depends on it. | A, D |
| 6 | The Salesforce record-triggered flow can process composite upserts of 200 records at the drain rate of about 250 records per second. To be confirmed with the Salesforce team. | The flow runs on every inserted or updated payment, and its governor limits are owned by the Salesforce team. | A |
| 7 | 180 days of operational history outside Salesforce is enough for investigations and disputes. 400 days of payment state covers late reversals and chargebacks. To be confirmed with Legal and Finance for each country. | No retention rules were given for integration data, and the three data protection laws favor keeping as little as possible. | A, C |
