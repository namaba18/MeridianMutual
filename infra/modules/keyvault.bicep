// A Key Vault for one group of secrets (NFR6). The design uses two vaults:
//   - webhooks:   provider webhook secrets, readable only by payment-intake
//   - salesforce: the Salesforce client secret, readable only by the salesforce-writers
// Key Vault has no per-vault charge, so splitting by reader gives least privilege at no extra cost
// (role assignments on individual secrets would require the secrets to exist before deployment).
//
// Secret VALUES are never set by this code: they are loaded by the Salesforce team and the
// provider onboarding process. Rotations add a "-next" version that both readers accept.

param name string
param location string

@description('Principal ids allowed to read secrets (Key Vault Secrets User).')
param secretReaderPrincipalIds array

param privateEndpointsSubnetId string
param keyVaultPrivateDnsZoneId string
param logAnalyticsWorkspaceId string

@description('Days a deleted vault or secret can be recovered (7 to 90).')
@minValue(7)
@maxValue(90)
param softDeleteRetentionDays int

param tags object

var keyVaultSecretsUserRoleId = '4633458b-17de-408a-b874-0445c86b69e6'

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true // no access policies: Entra ID roles only
    enableSoftDelete: true
    softDeleteRetentionInDays: softDeleteRetentionDays
    enablePurgeProtection: true
    publicNetworkAccess: 'Disabled'
    networkAcls: {
      defaultAction: 'Deny'
      bypass: 'None'
    }
  }
}

module endpoint 'private-endpoint.bicep' = {
  name: 'pe-${name}'
  params: {
    name: 'pe-${name}'
    location: location
    subnetId: privateEndpointsSubnetId
    targetResourceId: vault.id
    groupId: 'vault'
    privateDnsZoneId: keyVaultPrivateDnsZoneId
    tags: tags
  }
}

resource readers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in secretReaderPrincipalIds: {
  name: guid(vault.id, principalId, keyVaultSecretsUserRoleId)
  scope: vault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsUserRoleId)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]

// Every secret read is audited in the platform workspace.
resource audit 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'audit-to-log-analytics'
  scope: vault
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'audit'
        enabled: true
      }
    ]
  }
}

output id string = vault.id
output name string = vault.name
output uri string = vault.properties.vaultUri
