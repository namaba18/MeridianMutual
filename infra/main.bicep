// Premium Payment Notifications: Azure infrastructure for ONE environment (dev, test or prod),
// deployed into an existing resource group. Nothing is deployed by this repository; see
// infra/README.md for how it is validated and docs/infra/AZURE_SERVICES.md for the choices.

targetScope = 'resourceGroup'

@description('Configuration of one country. Adding a country means adding one entry (NFR9).')
type countryConfig = {
  @description('ISO 3166-1 alpha-2 code, e.g. MX.')
  @minLength(2)
  @maxLength(2)
  code: string

  @description('Payment provider of the country, e.g. Quetzal.')
  provider: string
}

@description('A downstream team that consumes the canonical event (FR6). Added by that team through a pull request.')
type downstreamConsumer = {
  @description('Subscription name, e.g. finance.')
  name: string

  @description('Object id of the team\'s managed identity. Empty until the team provides it: the subscription is created, the role is not.')
  principalId: string
}

@description('Service Bus sizing for this environment.')
type serviceBusConfig = {
  @description('Premium messaging units.')
  capacity: 1 | 2 | 4

  zoneRedundant: bool
}

@description('Cosmos DB sizing for this environment.')
type cosmosConfig = {
  @description('Autoscale ceiling (RU/s) of notifications and payment-state; the floor is 10% of it.')
  @minValue(1000)
  maxThroughput: int

  zoneRedundant: bool

  backupTier: 'Continuous7Days' | 'Continuous30Days'
}

@description('Container Apps sizing for this environment.')
type containerAppsConfig = {
  zoneRedundant: bool

  @description('payment-intake replicas. Min 2 in prod: always warm, and survives losing one.')
  intakeMinReplicas: int
  intakeMaxReplicas: int

  @description('Fixed replicas of each country writer (1 or 2); total Salesforce concurrency must stay ≤ 5 (§6.3).')
  writerReplicas: int

  opsMinReplicas: int
}

@description('Alert routing for this environment.')
type alertingConfig = {
  enabled: bool

  @description('On-call rotation (paging tool address): payments at risk now.')
  pageEmail: string

  @description('Squad channel: needs attention within the working day.')
  notifyEmail: string
}

@description('Environment being deployed.')
@allowed([
  'dev'
  'test'
  'prod'
])
param environmentName string

@description('Region. One region serves the three countries (SCENARIO.md).')
param location string = resourceGroup().location

@description('Short workload name used in every resource name.')
@maxLength(6)
param workloadName string = 'pay'

@description('Countries served by this environment.')
@minLength(1)
param countries countryConfig[]

param serviceBus serviceBusConfig

param cosmos cosmosConfig

param containerApps containerAppsConfig

param alerting alertingConfig

@description('Resource id of the shared, platform-owned container registry (assumption 12).')
param containerRegistryId string

@description('Image tag to deploy: the Git commit SHA built by the pipeline.')
param imageTag string

@description('CIDR of the API Manager gateway subnet, the only caller allowed on the HTTP apps.')
param apiManagerSourceCidr string

@description('Downstream consumers of the canonical event.')
param downstreamConsumers downstreamConsumer[] = []

@description('Resource id of the platform Log Analytics workspace (it already exists).')
param logAnalyticsWorkspaceId string

@description('Network address plan of this environment.')
param network object

@description('Tags applied to every resource.')
param tags object = {}

var baseName = '${workloadName}-${environmentName}'
// Globally unique, stable per resource group: needed for Key Vault names.
var uniqueSuffix = substring(uniqueString(resourceGroup().id), 0, 6)
var countryCodes = map(countries, c => c.code)
// Purge protection is on everywhere. Prod keeps deleted vaults and secrets 90 days; dev and test
// keep them 7 days (the minimum) so a vault deleted while experimenting can be recreated soon.
var softDeleteRetentionDays = environmentName == 'prod' ? 90 : 7
var allTags = union(tags, {
  workload: 'premium-payment-notifications'
  environment: environmentName
  owner: 'payments-integration'
})

module networkModule 'modules/network.bicep' = {
  name: 'network'
  params: {
    baseName: baseName
    location: location
    addressPrefix: network.addressPrefix
    containerAppsSubnetPrefix: network.containerAppsSubnetPrefix
    privateEndpointsSubnetPrefix: network.privateEndpointsSubnetPrefix
    tags: allTags
  }
}

module monitoringModule 'modules/monitoring.bicep' = {
  name: 'monitoring'
  params: {
    baseName: baseName
    location: location
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    tags: allTags
  }
}

module identitiesModule 'modules/identities.bicep' = {
  name: 'identities'
  params: {
    baseName: baseName
    location: location
    countryCodes: countryCodes
    tags: allTags
  }
}

// Provider webhook secrets: only payment-intake can read them.
module webhookVault 'modules/keyvault.bicep' = {
  name: 'keyvault-webhooks'
  params: {
    name: 'kvwh-${baseName}-${uniqueSuffix}'
    location: location
    secretReaderPrincipalIds: [
      identitiesModule.outputs.intake.principalId
    ]
    privateEndpointsSubnetId: networkModule.outputs.privateEndpointsSubnetId
    keyVaultPrivateDnsZoneId: networkModule.outputs.privateDnsZoneIds.keyVault
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    softDeleteRetentionDays: softDeleteRetentionDays
    tags: allTags
  }
}

// Salesforce client secret: only the salesforce-writers can read it.
module salesforceVault 'modules/keyvault.bicep' = {
  name: 'keyvault-salesforce'
  params: {
    name: 'kvsf-${baseName}-${uniqueSuffix}'
    location: location
    secretReaderPrincipalIds: map(identitiesModule.outputs.writers, w => w.principalId)
    privateEndpointsSubnetId: networkModule.outputs.privateEndpointsSubnetId
    keyVaultPrivateDnsZoneId: networkModule.outputs.privateDnsZoneIds.keyVault
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    softDeleteRetentionDays: softDeleteRetentionDays
    tags: allTags
  }
}

module serviceBusModule 'modules/servicebus.bicep' = {
  name: 'servicebus'
  params: {
    name: 'sb-${baseName}-${uniqueSuffix}'
    location: location
    capacity: serviceBus.capacity
    zoneRedundant: serviceBus.zoneRedundant
    countryCodes: countryCodes
    intakePrincipalId: identitiesModule.outputs.intake.principalId
    writers: identitiesModule.outputs.writers
    opsPrincipalId: identitiesModule.outputs.ops.principalId
    downstreamSubscriptions: downstreamConsumers
    privateEndpointsSubnetId: networkModule.outputs.privateEndpointsSubnetId
    serviceBusPrivateDnsZoneId: networkModule.outputs.privateDnsZoneIds.serviceBus
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    tags: allTags
  }
}

module cosmosModule 'modules/cosmos.bicep' = {
  name: 'cosmos'
  params: {
    name: 'cosmos-${baseName}-${uniqueSuffix}'
    location: location
    zoneRedundant: cosmos.zoneRedundant
    backupTier: cosmos.backupTier
    maxThroughput: cosmos.maxThroughput
    intakePrincipalId: identitiesModule.outputs.intake.principalId
    writers: identitiesModule.outputs.writers
    opsPrincipalId: identitiesModule.outputs.ops.principalId
    privateEndpointsSubnetId: networkModule.outputs.privateEndpointsSubnetId
    cosmosPrivateDnsZoneId: networkModule.outputs.privateDnsZoneIds.cosmos
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    tags: allTags
  }
}

// The registry lives in a platform resource group: grant pull there.
var registrySubscriptionId = split(containerRegistryId, '/')[2]
var registryResourceGroup = split(containerRegistryId, '/')[4]
var registryName = last(split(containerRegistryId, '/'))

module acrPullModule 'modules/acr-pull.bicep' = {
  name: 'acr-pull-${baseName}'
  scope: resourceGroup(registrySubscriptionId, registryResourceGroup)
  params: {
    registryName: registryName
    principalIds: concat(
      [
        identitiesModule.outputs.intake.principalId
        identitiesModule.outputs.ops.principalId
      ],
      map(identitiesModule.outputs.writers, w => w.principalId)
    )
  }
}

module containerAppsModule 'modules/containerapps.bicep' = {
  name: 'containerapps'
  // Apps start only after they can pull images. The Key Vault, Service Bus and Cosmos DB modules,
  // including their role assignments, are implicit dependencies through the outputs used below.
  dependsOn: [
    acrPullModule
  ]
  params: {
    baseName: baseName
    location: location
    infrastructureSubnetId: networkModule.outputs.containerAppsSubnetId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    zoneRedundant: containerApps.zoneRedundant
    registryServer: '${registryName}.azurecr.io'
    imageTag: imageTag
    apiManagerSourceCidr: apiManagerSourceCidr
    intakeScale: {
      minReplicas: containerApps.intakeMinReplicas
      maxReplicas: containerApps.intakeMaxReplicas
    }
    writerReplicas: containerApps.writerReplicas
    opsMinReplicas: containerApps.opsMinReplicas
    identities: identitiesModule.outputs
    writerSubscriptions: serviceBusModule.outputs.writerSubscriptions
    serviceBusNamespace: serviceBusModule.outputs.fullyQualifiedNamespace
    topicName: serviceBusModule.outputs.topicName
    quarantineQueueName: serviceBusModule.outputs.quarantineQueueName
    cosmosEndpoint: cosmosModule.outputs.endpoint
    cosmosDatabaseName: cosmosModule.outputs.databaseName
    webhookVaultUri: webhookVault.outputs.uri
    salesforceVaultUri: salesforceVault.outputs.uri
    appInsightsConnectionString: monitoringModule.outputs.appInsightsConnectionString
    tags: allTags
  }
}

module containerAppsDns 'modules/containerapps-dns.bicep' = {
  name: 'containerapps-dns'
  params: {
    defaultDomain: containerAppsModule.outputs.defaultDomain
    staticIp: containerAppsModule.outputs.staticIp
    vnetId: networkModule.outputs.vnetId
    baseName: baseName
    tags: allTags
  }
}

module alertsModule 'modules/alerts.bicep' = {
  name: 'alerts'
  params: {
    baseName: baseName
    countries: countries
    appInsightsId: monitoringModule.outputs.appInsightsId
    serviceBusNamespaceId: serviceBusModule.outputs.namespaceId
    cosmosAccountId: cosmosModule.outputs.accountId
    pageEmail: alerting.pageEmail
    notifyEmail: alerting.notifyEmail
    enabled: alerting.enabled
    tags: allTags
  }
}

output vnetId string = networkModule.outputs.vnetId
output intakeFqdn string = containerAppsModule.outputs.intakeFqdn
output opsFqdn string = containerAppsModule.outputs.opsFqdn
output cosmosEndpoint string = cosmosModule.outputs.endpoint
output serviceBusNamespace string = serviceBusModule.outputs.fullyQualifiedNamespace
output appInsightsId string = monitoringModule.outputs.appInsightsId
output webhookVaultUri string = webhookVault.outputs.uri
output salesforceVaultUri string = salesforceVault.outputs.uri
