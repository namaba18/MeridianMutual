# Infrastructure as code

Azure infrastructure of Premium Payment Notifications, written in **Bicep**. **Nothing has been deployed.** The reasons behind each service and its cost are in [`docs/infra/AZURE_SERVICES.md`](../docs/infra/AZURE_SERVICES.md).

## Tool and version

| Item | Value |
|---|---|
| Language | Bicep (Azure-native: no state file to store and protect, `what-if` before every change) |
| Validated with | Bicep CLI **0.49.2** |
| Linter | Strict rules in [`bicepconfig.json`](bicepconfig.json): hard-coded URLs or locations, secrets in parameters or outputs, unused code and unnecessary dependencies are **errors** |

## Structure

```
infra/
├── main.bicep                    One environment, deployed into an existing resource group
├── bicepconfig.json              Linter rules (also enforced in CI)
├── modules/
│   ├── network.bicep             VNet, subnets, private DNS zones
│   ├── monitoring.bicep          Application Insights on the platform Log Analytics workspace
│   ├── identities.bicep          User-assigned identities: intake, ops, one writer per country
│   ├── keyvault.bicep            A Key Vault (used twice: webhooks, salesforce)
│   ├── private-endpoint.bicep    Private endpoint + DNS registration (reused by every data service)
│   ├── servicebus.bicep          Topic, per-country and downstream subscriptions, quarantine queue
│   ├── cosmos.bicep              notifications, payment-state, replay-audit + data roles
│   ├── acr-pull.bicep            AcrPull on the shared registry (deployed in its resource group)
│   ├── containerapps.bicep       Internal environment + intake + one writer per country + ops
│   ├── containerapps-dns.bicep   Private DNS for the internal environment
│   └── alerts.bicep              Action groups, per-country and platform alerts
└── params/
    ├── dev.bicepparam
    ├── test.bicepparam
    └── prod.bicepparam
```

**Least privilege lives next to the resource.** Each module grants roles on its own resource, at the smallest scope Azure allows (a Key Vault, a topic, a subscription, a Cosmos container). There is no separate role file, so whoever reads a module also sees who can use it:

| Identity | Can do | Cannot do |
|---|---|---|
| `id-…-intake` | Read webhook secrets; **send** to the topic and to quarantine; write `notifications` | Read any message; read the Salesforce secret |
| `id-…-sfwriter-<cc>` (one per country) | Read the Salesforce secret; **receive** from its own country's subscription; write `notifications` and `payment-state` | Read another country; send to the topic |
| `id-…-ops` | Read `notifications` and `payment-state`; read DLQs; replay to the topic; write `replay-audit` | Read any secret |
| Downstream teams | **Receive** from their own subscription | Anything else |

No identity can delete Cosmos DB documents (they expire through TTL), and no service accepts keys: Service Bus, Cosmos DB and Application Insights have local authentication disabled.

## Parameters per environment and per country

**Per country**: the `countries` list. Each entry generates its own Service Bus subscription and filter, writer identity, writer Container App, role assignments, and DLQ and freshness alerts. **Adding a country is adding one entry** (NFR9):

```bicep
param countries = [
  { code: 'MX', provider: 'Quetzal' }
  { code: 'CO', provider: 'Andina' }
  { code: 'CL', provider: 'Austral' }
]
```

**Per environment**:

| Setting | dev | test | prod | Why it differs |
|---|---|---|---|---|
| Service Bus Premium units / zones | 1 / no | 1 / no | 1 / **yes** | Same topology everywhere (private endpoints need Premium); zones only where availability matters |
| Cosmos DB autoscale ceiling | 1,000 RU/s | 4,000 RU/s | 4,000 RU/s | Test reproduces the month-end peak for load tests |
| Cosmos DB zones / backup | no / 7 days | no / 7 days | **yes / 30 days** | |
| Container Apps zones | no | no | **yes** | |
| payment-intake replicas | 1–3 | 1–5 | **2–10** | Prod keeps 2 warm replicas: no cold start within Andina's 5 s |
| Writer replicas per country | 1 | 1 | 1 | Throughput is bounded by Salesforce, not compute (§6.3) |
| payment-ops min replicas | 0 | 0 | 1 | |
| Key Vault soft-delete | 7 days | 7 days | 90 days | A deleted dev vault can be recreated within a week |
| Alerts | created, **disabled** | enabled, all to the squad | enabled, page + notify | Test is where the alerts themselves are tested |
| VNet | 10.40.0.0/22 | 10.41.0.0/22 | 10.42.0.0/22 | No overlap, so the environments can be peered |

The image tag comes from the `IMAGE_TAG` environment variable, set by the pipeline to the commit SHA it built (never `latest`). All subscription ids, CIDRs and email addresses in `params/` are **fictional placeholders**.

## How it was validated (without deploying)

From this folder:

```powershell
bicep build main.bicep --stdout > $null
bicep lint main.bicep
bicep build-params params\dev.bicepparam --stdout > $null
bicep build-params params\test.bicepparam --stdout > $null
bicep build-params params\prod.bicepparam --stdout > $null
```

All five pass with **no errors and no warnings** under the strict linter. Beyond that, I compiled the template and checked:

- every resource type is present, and resource names respect Azure's length and character rules;
- the Service Bus SQL filters and the role assignments (role and scope) are the intended ones;
- the Cosmos DB role assignments depend on their containers.

The same commands run in CI on every pull request (`.github/workflows/`).

The validation found real issues, all fixed: a lambda indexing a resource array (not allowed by Bicep), Cosmos role assignments that could be created before their containers, unnecessary dependencies, a 90-day Key Vault retention that would block recreating dev vaults, and a writer autoscaling rule that contradicted the Salesforce concurrency cap.

**Not validated, because it needs an Azure subscription:**

| Check | How it would be done | Where |
|---|---|---|
| `what-if` against a real resource group | `az deployment group what-if` | CD, before every apply |
| The KQL queries of the log alerts | Run them in the Log Analytics workspace | Test environment |
| Resource provider quirks (SKU availability per region, zone support) | First deployment to dev | CD |

## Deploying (done by the CD pipeline, not by hand)

```bash
az deployment group create \
  --resource-group rg-payments-<env> \
  --template-file infra/main.bicep \
  --parameters infra/params/<env>.bicepparam
```

**Prerequisites**, provided by the platform team:

1. The resource group of each environment.
2. The shared container registry and Log Analytics workspace (assumption 12).
3. Private connectivity from the API Manager to the VNet, and its source CIDR (assumption 8).
4. For the pipeline's identity: **Contributor** on the resource group, plus **Role Based Access Control Administrator with a condition** that allows assigning only the roles this template uses (Key Vault Secrets User, Azure Service Bus Data Sender/Receiver, AcrPull). Owner or unrestricted User Access Administrator is not needed and not wanted.

**After the first deployment**, outside this code:

- **Secret values** are loaded into the two vaults: `webhook-secret-quetzal`, `webhook-secret-andina` and `salesforce-client-secret`, with a `-next` version during rotations. The vaults have no public access, so this is done from inside the network (pipeline runner or bastion), never from a laptop.
- **Downstream teams** add their subscription and identity to `downstreamConsumers` through a pull request.

## Known gaps

- **Private DNS zones** are created per environment. In a hub-and-spoke landing zone they would live in the hub and be referenced instead.
- **NSGs and egress control** for the subnets are not defined. Production would route egress through the platform firewall, with a fixed public IP the Salesforce team can allowlist.
- **Diagnostic settings** send everything to the platform workspace. Retention and cost of those logs are governed at platform level.
- **The writer metrics used by alerts** (`writer.records`, `writer.freshness.seconds`, `writer.salesforce.calls.rolling24h`) are a contract for `salesforce-writer`, which is designed but not built.
