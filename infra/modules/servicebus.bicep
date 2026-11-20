// Messaging (SOLUTION_DESIGN §1, §4, §5; docs/api/README.md):
//   - topic premium-payment-events, with duplicate detection on messageId = eventId (§4.2 layer 2)
//   - one session-enabled subscription per country for salesforce-writer (§4.4), filtered by the
//     "country" message property, each with its own dead-letter queue
//   - one subscription per downstream consumer (FR6), added by that team through a pull request
//   - queue payments-quarantine for authentic notifications that cannot be mapped (§5.2)
// Authentication is Entra ID only (no SAS keys) and the namespace has no public endpoint.

param name string
param location string

@description('Premium messaging units (1 handles far more than the 40/s peak; see AZURE_SERVICES.md).')
@allowed([
  1
  2
  4
])
param capacity int

param zoneRedundant bool

@description('Country codes; one salesforce-writer subscription per country.')
param countryCodes array

param intakePrincipalId string

@description('salesforce-writer identities: [{ country, principalId }], one per country.')
param writers array

param opsPrincipalId string

@description('Downstream consumers: [{ name, principalId }]. principalId may be empty until the team provides it.')
param downstreamSubscriptions array

param privateEndpointsSubnetId string
param serviceBusPrivateDnsZoneId string
param logAnalyticsWorkspaceId string
param tags object

var topicName = 'premium-payment-events'
var quarantineQueueName = 'payments-quarantine'
var senderRoleId = '69a216fc-b8fb-44d8-bc22-1f3c2cd27a39' // Azure Service Bus Data Sender
var receiverRoleId = '4f6d3b9b-027b-4f4c-9142-0e5a2a2247e0' // Azure Service Bus Data Receiver

resource namespace 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: name
  location: location
  tags: tags
  sku: {
    name: 'Premium'
    tier: 'Premium'
    capacity: capacity
  }
  properties: {
    disableLocalAuth: true // no SAS keys: managed identities only
    publicNetworkAccess: 'Disabled'
    minimumTlsVersion: '1.2'
    zoneRedundant: zoneRedundant
  }
}

resource topic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: namespace
  name: topicName
  properties: {
    requiresDuplicateDetection: true
    duplicateDetectionHistoryTimeWindow: 'P1D'
    defaultMessageTimeToLive: 'P14D'
    maxSizeInMegabytes: 5120
  }
}

// One subscription per country for salesforce-writer.
resource writerSubscriptions 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = [for code in countryCodes: {
  parent: topic
  name: 'sf-writer-${toLower(code)}'
  properties: {
    requiresSession: true // SessionId = paymentKey: one payment at a time (§4.4)
    lockDuration: 'PT1M'
    maxDeliveryCount: 10 // only for unexpected errors; data errors are dead-lettered at once (§5.1)
    defaultMessageTimeToLive: 'P7D'
    deadLetteringOnMessageExpiration: true // never lose an event, even an expired one
    deadLetteringOnFilterEvaluationExceptions: true
  }
}]

// Replacing $Default (match-all) with the country filter. Replays carry replayTarget so they reach
// only the subscription they came from (§5.3).
resource writerFilters 'Microsoft.ServiceBus/namespaces/topics/subscriptions/rules@2022-10-01-preview' = [for (code, i) in countryCodes: {
  parent: writerSubscriptions[i]
  name: '$Default'
  properties: {
    filterType: 'SqlFilter'
    sqlFilter: {
      sqlExpression: 'country = \'${code}\' AND (replayTarget IS NULL OR replayTarget = \'sf-writer-${toLower(code)}\')'
    }
  }
}]

resource downstream 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = [for consumer in downstreamSubscriptions: {
  parent: topic
  name: consumer.name
  properties: {
    lockDuration: 'PT1M'
    maxDeliveryCount: 10
    defaultMessageTimeToLive: 'P7D'
    deadLetteringOnMessageExpiration: true
    deadLetteringOnFilterEvaluationExceptions: true
  }
}]

// Downstream consumers receive every country but never our internal replays.
resource downstreamFilters 'Microsoft.ServiceBus/namespaces/topics/subscriptions/rules@2022-10-01-preview' = [for (consumer, i) in downstreamSubscriptions: {
  parent: downstream[i]
  name: '$Default'
  properties: {
    filterType: 'SqlFilter'
    sqlFilter: {
      sqlExpression: 'replayTarget IS NULL'
    }
  }
}]

resource quarantine 'Microsoft.ServiceBus/namespaces/queues@2022-10-01-preview' = {
  parent: namespace
  name: quarantineQueueName
  properties: {
    lockDuration: 'PT5M'
    maxDeliveryCount: 10
    defaultMessageTimeToLive: 'P30D' // time for operations to review; expired messages are dead-lettered, not lost
    deadLetteringOnMessageExpiration: true
    maxSizeInMegabytes: 1024
  }
}

// ---------------------------------------------------------------- least-privilege data roles

// payment-intake: send to the topic and to quarantine. Nothing else.
resource intakeSendsEvents 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(topic.id, intakePrincipalId, senderRoleId)
  scope: topic
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', senderRoleId)
    principalId: intakePrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource intakeSendsQuarantine 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(quarantine.id, intakePrincipalId, senderRoleId)
  scope: quarantine
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', senderRoleId)
    principalId: intakePrincipalId
    principalType: 'ServicePrincipal'
  }
}

// salesforce-writer of a country: receive from that country's subscription only.
resource writerReceives 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for (code, i) in countryCodes: {
  name: guid(writerSubscriptions[i].id, writers[i].principalId, receiverRoleId)
  scope: writerSubscriptions[i]
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', receiverRoleId)
    principalId: writers[i].principalId
    principalType: 'ServicePrincipal'
  }
}]

// payment-ops: read dead-letter queues (receive on each writer subscription and on quarantine)
// and replay to the topic (§5.3). Service Bus has no DLQ-only scope, so the receive role also
// covers the live subscription; replays are restricted to the payments-ops-replay role in the app.
resource opsReceivesWriterDlq 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for (code, i) in countryCodes: {
  name: guid(writerSubscriptions[i].id, opsPrincipalId, receiverRoleId)
  scope: writerSubscriptions[i]
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', receiverRoleId)
    principalId: opsPrincipalId
    principalType: 'ServicePrincipal'
  }
}]

resource opsReceivesQuarantine 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(quarantine.id, opsPrincipalId, receiverRoleId)
  scope: quarantine
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', receiverRoleId)
    principalId: opsPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource opsReplaysToTopic 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(topic.id, opsPrincipalId, senderRoleId)
  scope: topic
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', senderRoleId)
    principalId: opsPrincipalId
    principalType: 'ServicePrincipal'
  }
}

// Downstream consumers: receive from their own subscription only.
resource downstreamReceives 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for (consumer, i) in downstreamSubscriptions: if (!empty(consumer.principalId)) {
  name: guid(downstream[i].id, consumer.principalId, receiverRoleId)
  scope: downstream[i]
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', receiverRoleId)
    principalId: consumer.principalId
    principalType: 'ServicePrincipal'
  }
}]

// ---------------------------------------------------------------- network and diagnostics

module endpoint 'private-endpoint.bicep' = {
  name: 'pe-${name}'
  params: {
    name: 'pe-${name}'
    location: location
    subnetId: privateEndpointsSubnetId
    targetResourceId: namespace.id
    groupId: 'namespace'
    privateDnsZoneId: serviceBusPrivateDnsZoneId
    tags: tags
  }
}

resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: namespace
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'AllMetrics'
        enabled: true
      }
    ]
  }
}

output namespaceId string = namespace.id
output namespaceName string = namespace.name
output fullyQualifiedNamespace string = '${namespace.name}.servicebus.windows.net'
output topicName string = topicName
output quarantineQueueName string = quarantineQueueName

@description('Writer subscriptions, in the same order as countryCodes.')
output writerSubscriptions array = [for (code, i) in countryCodes: {
  country: code
  name: writerSubscriptions[i].name
  id: writerSubscriptions[i].id
}]
