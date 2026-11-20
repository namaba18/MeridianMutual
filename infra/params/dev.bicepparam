using '../main.bicep'

// Development: lowest cost that still keeps the same topology as prod (private endpoints,
// no keys). Identifiers below are fictional placeholders.

param environmentName = 'dev'

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

// Premium even in dev: Standard has no private endpoints, and dev must keep prod's topology.
param serviceBus = {
  capacity: 1
  zoneRedundant: false
}

// Autoscale 100-1000 RU/s per container: enough for functional tests.
param cosmos = {
  maxThroughput: 1000
  zoneRedundant: false
  backupTier: 'Continuous7Days'
}

param containerApps = {
  zoneRedundant: false
  intakeMinReplicas: 1
  intakeMaxReplicas: 3
  writerReplicas: 1
  opsMinReplicas: 0 // scales to zero when nobody uses it
}

param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-registry/providers/Microsoft.ContainerRegistry/registries/meridianplatformacr'

// Set by the pipeline to the commit SHA it built.
param imageTag = readEnvironmentVariable('IMAGE_TAG', 'local-build')

param apiManagerSourceCidr = '10.10.1.0/27'

// Created but disabled: dev would only produce noise. Addresses are fictional.
param alerting = {
  enabled: false
  pageEmail: 'payments-oncall@meridian.example'
  notifyEmail: 'payments-squad@meridian.example'
}

// Consumer teams add themselves here by pull request (principalId of their managed identity).
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

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring-dev/providers/Microsoft.OperationalInsights/workspaces/log-platform-dev'

param network = {
  addressPrefix: '10.40.0.0/22'
  containerAppsSubnetPrefix: '10.40.0.0/23'
  privateEndpointsSubnetPrefix: '10.40.2.0/26'
}
