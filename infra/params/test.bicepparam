using '../main.bicep'

// Test: same topology as prod, connected to the providers' sandboxes. Identifiers are fictional.

param environmentName = 'test'

param countries = [
  {
    code: 'MX'
    provider: 'Quetzal'
  }
  {
    code: 'CO'
    provider: 'Andina'
  }
  {
    code: 'CL'
    provider: 'Austral'
  }
]

param serviceBus = {
  capacity: 1
  zoneRedundant: false
}

// Same ceiling as prod, so load tests reproduce the month-end peak.
param cosmos = {
  maxThroughput: 4000
  zoneRedundant: false
  backupTier: 'Continuous7Days'
}

param containerApps = {
  zoneRedundant: false
  intakeMinReplicas: 1
  intakeMaxReplicas: 5
  writerReplicas: 1
  opsMinReplicas: 0
}

param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-registry/providers/Microsoft.ContainerRegistry/registries/meridianplatformacr'

param imageTag = readEnvironmentVariable('IMAGE_TAG', 'local-build')

param apiManagerSourceCidr = '10.11.1.0/27'

// Enabled, but both routes go to the squad: test is where alerts themselves are tested.
param alerting = {
  enabled: true
  pageEmail: 'payments-squad@meridian.example'
  notifyEmail: 'payments-squad@meridian.example'
}

param downstreamConsumers = [
  {
    name: 'finance'
    principalId: ''
  }
  {
    name: 'notifications'
    principalId: ''
  }
]

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring-test/providers/Microsoft.OperationalInsights/workspaces/log-platform-test'

param network = {
  addressPrefix: '10.41.0.0/22'
  containerAppsSubnetPrefix: '10.41.0.0/23'
  privateEndpointsSubnetPrefix: '10.41.2.0/26'
}
