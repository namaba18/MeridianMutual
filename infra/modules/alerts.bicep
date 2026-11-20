// Alerts (NFR7; SOLUTION_DESIGN §5.2, §6.3; INC-0412). Two routes:
//   - page:   on-call engineer, for anything that risks losing or delaying payments now
//   - notify: squad channel, for anything that needs attention within the working day
// Lesson from INC-0412: v1 had a DLQ alert for Mexico only. Here every per-country alert is
// generated from the countries list, so a new country gets its alerts automatically.
//
// Log-based alerts read the application metrics:
//   intake.notifications{provider,outcome,reason}   emitted by payment-intake (practical/)
//   writer.records{country,outcome}                 emitted by salesforce-writer (design contract)
//   writer.freshness.seconds{country}               receivedAt -> recorded in Salesforce
//   writer.salesforce.calls.rolling24h              the budget guard's own counter

param baseName string

@description('Countries: [{ code, provider }].')
param countries array

param appInsightsId string
param serviceBusNamespaceId string
param cosmosAccountId string

@description('Email (or paging-tool address) of the on-call rotation.')
param pageEmail string

@description('Email of the squad channel.')
param notifyEmail string

@description('Disabled in dev to avoid noise; enabled in test (to test the alerts) and prod.')
param enabled bool

param tags object

// ---------------------------------------------------------------- action groups

resource page 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: 'ag-${baseName}-page'
  location: 'global'
  tags: tags
  properties: {
    groupShortName: 'pay-page'
    enabled: true
    emailReceivers: [
      {
        name: 'on-call'
        emailAddress: pageEmail
        useCommonAlertSchema: true
      }
    ]
  }
}

resource notify 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: 'ag-${baseName}-notify'
  location: 'global'
  tags: tags
  properties: {
    groupShortName: 'pay-notify'
    enabled: true
    emailReceivers: [
      {
        name: 'squad'
        emailAddress: notifyEmail
        useCommonAlertSchema: true
      }
    ]
  }
}

// ---------------------------------------------------------------- per country (log-based)

// Any dead-lettered record in a country (INC-0412 would have paged within 5 minutes).
resource deadLettered 'Microsoft.Insights/scheduledQueryRules@2022-06-15' = [for c in countries: {
  name: 'alert-${baseName}-dlq-${toLower(c.code)}'
  location: resourceGroup().location
  tags: tags
  properties: {
    displayName: 'Payments ${c.code}: records dead-lettered'
    description: 'salesforce-writer dead-lettered at least one ${c.code} payment. Check the reason in the Payment Ops API; runbook in docs/operations.'
    severity: 1
    enabled: enabled
    evaluationFrequency: 'PT5M'
    windowSize: 'PT5M'
    scopes: [
      appInsightsId
    ]
    criteria: {
      allOf: [
        {
          query: 'customMetrics | where name == "writer.records" | where tostring(customDimensions.country) == "${c.code}" and tostring(customDimensions.outcome) == "DEAD_LETTERED" | summarize records = sum(valueSum)'
          timeAggregation: 'Total'
          metricMeasureColumn: 'records'
          operator: 'GreaterThan'
          threshold: 0
          failingPeriods: {
            numberOfEvaluationPeriods: 1
            minFailingPeriodsToAlert: 1
          }
        }
      ]
    }
    autoMitigate: true
    actions: {
      actionGroups: [
        page.id
      ]
    }
  }
}]

// Freshness SLO: p95 from notification to Salesforce over 5 minutes (NFR2).
resource freshness 'Microsoft.Insights/scheduledQueryRules@2022-06-15' = [for c in countries: {
  name: 'alert-${baseName}-freshness-${toLower(c.code)}'
  location: resourceGroup().location
  tags: tags
  properties: {
    displayName: 'Payments ${c.code}: p95 freshness over 5 minutes'
    description: '95% of ${c.code} payments must reach Salesforce within 5 minutes. Usually Salesforce unavailable or the API limit reached.'
    severity: 2
    enabled: enabled
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    scopes: [
      appInsightsId
    ]
    criteria: {
      allOf: [
        {
          query: 'customMetrics | where name == "writer.freshness.seconds" | where tostring(customDimensions.country) == "${c.code}" | summarize p95 = percentile(value, 95)'
          timeAggregation: 'Maximum'
          metricMeasureColumn: 'p95'
          operator: 'GreaterThan'
          threshold: 300
          failingPeriods: {
            numberOfEvaluationPeriods: 2
            minFailingPeriodsToAlert: 2
          }
        }
      ]
    }
    autoMitigate: true
    actions: {
      actionGroups: [
        page.id
      ]
    }
  }
}]

// ---------------------------------------------------------------- intake (log-based)

// payment-intake answering 503: providers are retrying and Andina gives up after 24 h (ADR-0003).
resource intakeUnavailable 'Microsoft.Insights/scheduledQueryRules@2022-06-15' = {
  name: 'alert-${baseName}-intake-unavailable'
  location: resourceGroup().location
  tags: tags
  properties: {
    displayName: 'Payments intake: more than 1% of webhooks answered 503'
    description: 'Notifications are not being accepted. Check Service Bus health first (ADR-0003).'
    severity: 1
    enabled: enabled
    evaluationFrequency: 'PT5M'
    windowSize: 'PT5M'
    scopes: [
      appInsightsId
    ]
    criteria: {
      allOf: [
        {
          query: 'customMetrics | where name == "intake.notifications" | extend outcome = tostring(customDimensions.outcome) | summarize total = sum(valueSum), unavailable = sumif(valueSum, outcome == "UNAVAILABLE") | extend ratio = iif(total == 0, 0.0, unavailable / total)'
          timeAggregation: 'Maximum'
          metricMeasureColumn: 'ratio'
          operator: 'GreaterThan'
          threshold: json('0.01')
          failingPeriods: {
            numberOfEvaluationPeriods: 1
            minFailingPeriodsToAlert: 1
          }
        }
      ]
    }
    autoMitigate: true
    actions: {
      actionGroups: [
        page.id
      ]
    }
  }
}

// Signature failures: a secret rotation gone wrong, or someone probing the endpoint.
resource signatureFailures 'Microsoft.Insights/scheduledQueryRules@2022-06-15' = {
  name: 'alert-${baseName}-invalid-signatures'
  location: resourceGroup().location
  tags: tags
  properties: {
    displayName: 'Payments intake: invalid signatures'
    description: 'More than 20 webhooks rejected with 401 in 15 minutes for one provider.'
    severity: 2
    enabled: enabled
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    scopes: [
      appInsightsId
    ]
    criteria: {
      allOf: [
        {
          query: 'customMetrics | where name == "intake.notifications" | where tostring(customDimensions.outcome) == "INVALID_SIGNATURE" | summarize rejected = sum(valueSum) by provider = tostring(customDimensions.provider)'
          timeAggregation: 'Maximum'
          metricMeasureColumn: 'rejected'
          dimensions: [
            {
              name: 'provider'
              operator: 'Include'
              values: [
                '*'
              ]
            }
          ]
          operator: 'GreaterThan'
          threshold: 20
          failingPeriods: {
            numberOfEvaluationPeriods: 1
            minFailingPeriodsToAlert: 1
          }
        }
      ]
    }
    autoMitigate: true
    actions: {
      actionGroups: [
        notify.id
      ]
    }
  }
}

// Salesforce API budget guard (SOLUTION_DESIGN §6.3): 60% of 150,000 calls in 24 h.
resource apiBudget 'Microsoft.Insights/scheduledQueryRules@2022-06-15' = {
  name: 'alert-${baseName}-salesforce-api-budget'
  location: resourceGroup().location
  tags: tags
  properties: {
    displayName: 'Payments: Salesforce API usage over 60% of the daily budget'
    description: 'At 80% the writers widen the batch window; at 95% they stop. Coordinate with the Salesforce team.'
    severity: 2
    enabled: enabled
    evaluationFrequency: 'PT15M'
    windowSize: 'PT15M'
    scopes: [
      appInsightsId
    ]
    criteria: {
      allOf: [
        {
          query: 'customMetrics | where name == "writer.salesforce.calls.rolling24h" | summarize calls = max(valueMax)'
          timeAggregation: 'Maximum'
          metricMeasureColumn: 'calls'
          operator: 'GreaterThan'
          threshold: 90000
          failingPeriods: {
            numberOfEvaluationPeriods: 1
            minFailingPeriodsToAlert: 1
          }
        }
      ]
    }
    autoMitigate: true
    actions: {
      actionGroups: [
        notify.id
      ]
    }
  }
}

// ---------------------------------------------------------------- platform metrics

// Safety net independent of our own telemetry: anything in a dead-letter queue or in quarantine.
resource brokerDeadLetters 'Microsoft.Insights/metricAlerts@2018-03-01' = {
  name: 'alert-${baseName}-servicebus-deadletters'
  location: 'global'
  tags: tags
  properties: {
    description: 'Messages in a dead-letter queue of the topic subscriptions or the quarantine queue, independent of application telemetry.'
    severity: 2
    enabled: enabled
    scopes: [
      serviceBusNamespaceId
    ]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT5M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          criterionType: 'StaticThresholdCriterion'
          name: 'dead-lettered'
          metricNamespace: 'Microsoft.ServiceBus/namespaces'
          metricName: 'DeadletteredMessages'
          dimensions: [
            {
              name: 'EntityName'
              operator: 'Include'
              values: [
                '*'
              ]
            }
          ]
          operator: 'GreaterThan'
          threshold: 0
          timeAggregation: 'Maximum'
        }
      ]
    }
    autoMitigate: true
    actions: [
      {
        actionGroupId: notify.id
      }
    ]
  }
}

// Cosmos DB throttling: autoscale ceiling too low, or an unexpected query pattern (ADR-0002).
resource cosmosThrottling 'Microsoft.Insights/metricAlerts@2018-03-01' = {
  name: 'alert-${baseName}-cosmos-throttling'
  location: 'global'
  tags: tags
  properties: {
    description: 'Cosmos DB answered 429 (throttled) more than 10 times in 15 minutes.'
    severity: 3
    enabled: enabled
    scopes: [
      cosmosAccountId
    ]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          criterionType: 'StaticThresholdCriterion'
          name: 'throttled'
          metricNamespace: 'Microsoft.DocumentDB/databaseAccounts'
          metricName: 'TotalRequests'
          dimensions: [
            {
              name: 'StatusCode'
              operator: 'Include'
              values: [
                '429'
              ]
            }
          ]
          operator: 'GreaterThan'
          threshold: 10
          timeAggregation: 'Count'
        }
      ]
    }
    autoMitigate: true
    actions: [
      {
        actionGroupId: notify.id
      }
    ]
  }
}
