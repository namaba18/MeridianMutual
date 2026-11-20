using '../main.bicep'

// Production. Identifiers are fictional.

param environmentName = 'prod'

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

// One messaging unit covers the 40/s burst many times over; zone redundancy for availability.
param serviceBus = {
  capacity: 1
  zoneRedundant: true
}

// Peak ≈ 1,400 RU/s across both containers (SOLUTION_DESIGN §6); 4,000 per container leaves room
// for replays. Autoscale bills 400 RU/s per container on the 26 normal days of the month.
param cosmos = {
  maxThroughput: 4000
  zoneRedundant: true
  backupTier: 'Continuous30Days'
}

// Intake: 2 warm replicas across zones; 40/s needs ~2 requests in flight, so 10 is ample (§6.1).
// Writers: 1 per country; 3 calls in flight in normal operation, within the cap of 5 (§6.3).
param containerApps = {
  zoneRedundant: true
  intakeMinReplicas: 2
  intakeMaxReplicas: 10
  writerReplicas: 1
  opsMinReplicas: 1
}

param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-registry/providers/Microsoft.ContainerRegistry/registries/meridianplatformacr'

param imageTag = readEnvironmentVariable('IMAGE_TAG', 'local-build')

param apiManagerSourceCidr = '10.12.1.0/27'

param alerting = {
  enabled: true
  pageEmail: 'payments-oncall@meridian.example'
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

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring-prod/providers/Microsoft.OperationalInsights/workspaces/log-platform-prod'

param network = {
  addressPrefix: '10.42.0.0/22'
  containerAppsSubnetPrefix: '10.42.0.0/23'
  privateEndpointsSubnetPrefix: '10.42.2.0/26'
}
