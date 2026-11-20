// Operational state outside Salesforce (SOLUTION_DESIGN §7, ADR-0002): Cosmos DB for NoSQL with
//   - notifications   one document per business event; FR4 duplicate lookup and FR7 search
//   - payment-state   latest state applied to Salesforce per payment; FR5 ordering rule
//   - replay-audit    who replayed what (§5.3)
// Partitioning, indexing and TTL follow §7.3 and §7.4. Access is Entra ID only, with a custom
// data role that cannot delete documents (documents expire through TTL, never by hand).

param name string
param location string
param zoneRedundant bool

@description('Continuous backup window: Continuous7Days or Continuous30Days.')
@allowed([
  'Continuous7Days'
  'Continuous30Days'
])
param backupTier string

@description('Autoscale ceiling (RU/s) of notifications and payment-state. The floor is 10% of it.')
@minValue(1000)
param maxThroughput int

param intakePrincipalId string

@description('salesforce-writer identities: [{ country, principalId }].')
param writers array

param opsPrincipalId string
param privateEndpointsSubnetId string
param cosmosPrivateDnsZoneId string
param logAnalyticsWorkspaceId string
param tags object

var databaseName = 'payments'
var dataReaderRoleId = '00000000-0000-0000-0000-000000000001' // Cosmos DB Built-in Data Reader
var days = 86400

resource account 'Microsoft.DocumentDB/databaseAccounts@2024-05-15' = {
  name: name
  location: location
  tags: tags
  kind: 'GlobalDocumentDB'
  properties: {
    databaseAccountOfferType: 'Standard'
    locations: [
      {
        locationName: location
        failoverPriority: 0
        isZoneRedundant: zoneRedundant
      }
    ]
    // Each payment is written by one writer at a time (Service Bus sessions), and ETag checks
    // protect the rest; session consistency is enough and cheaper than strong.
    consistencyPolicy: {
      defaultConsistencyLevel: 'Session'
    }
    disableLocalAuth: true // no account keys: managed identities only
    disableKeyBasedMetadataWriteAccess: true
    publicNetworkAccess: 'Disabled'
    minimalTlsVersion: 'Tls12'
    backupPolicy: {
      type: 'Continuous'
      continuousModeProperties: {
        tier: backupTier
      }
    }
  }
}

resource database 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases@2024-05-15' = {
  parent: account
  name: databaseName
  properties: {
    resource: {
      id: databaseName
    }
  }
}

resource notifications 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2024-05-15' = {
  parent: database
  name: 'notifications'
  properties: {
    resource: {
      id: 'notifications'
      partitionKey: {
        paths: [
          '/paymentKey'
        ]
        kind: 'Hash'
      }
      defaultTtl: 180 * days
      // Only the paths operations searches by (FR7) are indexed; everything else is excluded,
      // which keeps every write cheap.
      indexingPolicy: {
        indexingMode: 'consistent'
        automatic: true
        includedPaths: [
          {
            path: '/providerTransactionId/?'
          }
          {
            path: '/attempts/[]/providerEventId/?'
          }
          {
            path: '/processingStatus/?'
          }
          {
            path: '/country/?'
          }
          {
            path: '/statusChangedAt/?'
          }
        ]
        excludedPaths: [
          {
            path: '/*'
          }
        ]
        compositeIndexes: [
          [
            {
              path: '/country'
              order: 'ascending'
            }
            {
              path: '/processingStatus'
              order: 'ascending'
            }
            {
              path: '/statusChangedAt'
              order: 'descending'
            }
          ]
        ]
      }
    }
    options: {
      autoscaleSettings: {
        maxThroughput: maxThroughput
      }
    }
  }
}

resource paymentState 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2024-05-15' = {
  parent: database
  name: 'payment-state'
  properties: {
    resource: {
      id: 'payment-state'
      partitionKey: {
        paths: [
          '/paymentKey'
        ]
        kind: 'Hash'
      }
      defaultTtl: 400 * days // renewed on every update
      // Read and written by id only: no index at all.
      indexingPolicy: {
        indexingMode: 'none'
        automatic: false
      }
    }
    options: {
      autoscaleSettings: {
        maxThroughput: maxThroughput
      }
    }
  }
}

resource replayAudit 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2024-05-15' = {
  parent: database
  name: 'replay-audit'
  properties: {
    resource: {
      id: 'replay-audit'
      partitionKey: {
        paths: [
          '/country'
        ]
        kind: 'Hash'
      }
      defaultTtl: 730 * days
      indexingPolicy: {
        indexingMode: 'consistent'
        automatic: true
        includedPaths: [
          {
            path: '/startedAt/?'
          }
          {
            path: '/user/?'
          }
        ]
        excludedPaths: [
          {
            path: '/*'
          }
        ]
      }
    }
    options: {
      autoscaleSettings: {
        maxThroughput: 1000 // the minimum: a few writes per day
      }
    }
  }
}

// ---------------------------------------------------------------- least-privilege data roles

// Read, create, replace and upsert items, and query. No delete: documents expire through TTL.
resource writerRole 'Microsoft.DocumentDB/databaseAccounts/sqlRoleDefinitions@2024-05-15' = {
  parent: account
  name: guid(account.id, 'payments-data-writer')
  properties: {
    roleName: 'Payments Data Writer (no delete)'
    type: 'CustomRole'
    assignableScopes: [
      account.id
    ]
    permissions: [
      {
        dataActions: [
          'Microsoft.DocumentDB/databaseAccounts/readMetadata'
          'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers/executeQuery'
          'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers/items/read'
          'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers/items/create'
          'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers/items/replace'
          'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers/items/upsert'
        ]
      }
    ]
  }
}

var notificationsScope = '${account.id}/dbs/${databaseName}/colls/notifications'
var paymentStateScope = '${account.id}/dbs/${databaseName}/colls/payment-state'
var replayAuditScope = '${account.id}/dbs/${databaseName}/colls/replay-audit'
var readerRoleDefinitionId = '${account.id}/sqlRoleDefinitions/${dataReaderRoleId}'

// payment-intake: writes notifications only.
resource intakeWritesNotifications 'Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments@2024-05-15' = {
  parent: account
  dependsOn: [
    notifications
  ]
  name: guid(notificationsScope, intakePrincipalId, 'writer')
  properties: {
    roleDefinitionId: writerRole.id
    principalId: intakePrincipalId
    scope: notificationsScope
  }
}

// salesforce-writers: notifications and payment-state.
resource writersWriteNotifications 'Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments@2024-05-15' = [for writer in writers: {
  parent: account
  dependsOn: [
    notifications
  ]
  name: guid(notificationsScope, writer.principalId, 'writer')
  properties: {
    roleDefinitionId: writerRole.id
    principalId: writer.principalId
    scope: notificationsScope
  }
}]

resource writersWritePaymentState 'Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments@2024-05-15' = [for writer in writers: {
  parent: account
  dependsOn: [
    paymentState
  ]
  name: guid(paymentStateScope, writer.principalId, 'writer')
  properties: {
    roleDefinitionId: writerRole.id
    principalId: writer.principalId
    scope: paymentStateScope
  }
}]

// payment-ops: reads notifications and payment-state (FR7), writes only its own audit trail.
resource opsReadsNotifications 'Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments@2024-05-15' = {
  parent: account
  dependsOn: [
    notifications
  ]
  name: guid(notificationsScope, opsPrincipalId, 'reader')
  properties: {
    roleDefinitionId: readerRoleDefinitionId
    principalId: opsPrincipalId
    scope: notificationsScope
  }
}

resource opsReadsPaymentState 'Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments@2024-05-15' = {
  parent: account
  dependsOn: [
    paymentState
  ]
  name: guid(paymentStateScope, opsPrincipalId, 'reader')
  properties: {
    roleDefinitionId: readerRoleDefinitionId
    principalId: opsPrincipalId
    scope: paymentStateScope
  }
}

resource opsWritesReplayAudit 'Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments@2024-05-15' = {
  parent: account
  dependsOn: [
    replayAudit
  ]
  name: guid(replayAuditScope, opsPrincipalId, 'writer')
  properties: {
    roleDefinitionId: writerRole.id
    principalId: opsPrincipalId
    scope: replayAuditScope
  }
}

// ---------------------------------------------------------------- network and diagnostics

module endpoint 'private-endpoint.bicep' = {
  name: 'pe-${name}'
  params: {
    name: 'pe-${name}'
    location: location
    subnetId: privateEndpointsSubnetId
    targetResourceId: account.id
    groupId: 'Sql'
    privateDnsZoneId: cosmosPrivateDnsZoneId
    tags: tags
  }
}

resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: account
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'audit'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'Requests'
        enabled: true
      }
    ]
  }
}

output accountId string = account.id
output endpoint string = account.properties.documentEndpoint
output databaseName string = databaseName
