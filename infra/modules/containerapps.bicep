// Compute (ADR-0002): an internal Azure Container Apps environment in the VNet, running
//   - payment-intake       webhooks from the API Manager; HTTP-scaled; always warm (no cold start
//                          within Andina's 5 s)
//   - salesforce-writer-xx one app per country (SOLUTION_DESIGN §1.1), with a fixed replica count;
//                          a failure stays in one country
//   - payment-ops          FR7 lookups and audited replays
// Apps are reachable only from inside the VNet, and HTTP apps only from the API Manager's subnet.
// Every app runs as its own user-assigned identity: no secrets are passed in environment variables;
// apps read Key Vault, Service Bus and Cosmos DB with that identity.

param baseName string
param location string
param infrastructureSubnetId string
param logAnalyticsWorkspaceId string
param zoneRedundant bool

@description('Login server of the shared container registry, e.g. meridianacr.azurecr.io.')
param registryServer string

@description('Image tag to run: the Git commit SHA built by the pipeline. Never "latest".')
param imageTag string

@description('CIDR of the API Manager gateway subnet: the only source allowed to call the HTTP apps.')
param apiManagerSourceCidr string

@description('{ minReplicas, maxReplicas } of payment-intake.')
param intakeScale object

@description('Replicas of each country writer, fixed. Throughput is bounded by Salesforce, not compute: one replica batches 200 records per call, and more replicas would only push total Salesforce concurrency past the cap (§6.3).')
@minValue(1)
@maxValue(2)
param writerReplicas int

@description('Min replicas of payment-ops.')
param opsMinReplicas int

@description('Identities: { intake: {id, clientId}, writers: [{country, id, clientId}], ops: {id, clientId} }.')
param identities object

@description('Writer subscriptions: [{ country, name }], same order as identities.writers.')
param writerSubscriptions array

param serviceBusNamespace string
param topicName string
param quarantineQueueName string
param cosmosEndpoint string
param cosmosDatabaseName string
param webhookVaultUri string
param salesforceVaultUri string
param appInsightsConnectionString string
param tags object

// ---------------------------------------------------------------- environment

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: 'cae-${baseName}'
  location: location
  tags: tags
  properties: {
    vnetConfiguration: {
      infrastructureSubnetId: infrastructureSubnetId
      internal: true // no public IP: reachable only through the VNet
    }
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    zoneRedundant: zoneRedundant
    appLogsConfiguration: {
      destination: 'azure-monitor' // via the diagnostic setting below: no workspace key needed
    }
  }
}

resource environmentLogs 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: environment
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
  }
}

// ---------------------------------------------------------------- shared settings

var commonEnv = [
  {
    name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
    value: appInsightsConnectionString
  }
  {
    name: 'SPRING_PROFILES_ACTIVE'
    value: 'azure'
  }
  {
    name: 'INTAKE_SERVICEBUS_NAMESPACE'
    value: serviceBusNamespace
  }
  {
    name: 'INTAKE_COSMOS_ENDPOINT'
    value: cosmosEndpoint
  }
  {
    name: 'INTAKE_COSMOS_DATABASE'
    value: cosmosDatabaseName
  }
]

var healthProbes = [
  {
    type: 'Liveness'
    httpGet: {
      path: '/actuator/health/liveness'
      port: 8080
    }
    periodSeconds: 10
  }
  {
    type: 'Readiness'
    httpGet: {
      path: '/actuator/health/readiness'
      port: 8080
    }
    periodSeconds: 5
  }
]

var internalIngress = {
  external: true // "external" to the environment, i.e. visible in the VNet; the environment itself is internal
  targetPort: 8080
  transport: 'http'
  allowInsecure: false
  ipSecurityRestrictions: [
    {
      name: 'api-manager-only'
      action: 'Allow'
      ipAddressRange: apiManagerSourceCidr
    }
  ]
}

// ---------------------------------------------------------------- payment-intake

resource intake 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'ca-${baseName}-intake'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identities.intake.id}': {}
    }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: internalIngress
      registries: [
        {
          server: registryServer
          identity: identities.intake.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'payment-intake'
          image: '${registryServer}/payment-intake:${imageTag}'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv, [
            {
              name: 'AZURE_CLIENT_ID'
              value: identities.intake.clientId
            }
            {
              name: 'INTAKE_TOPIC'
              value: topicName
            }
            {
              name: 'INTAKE_QUARANTINE_QUEUE'
              value: quarantineQueueName
            }
            {
              name: 'INTAKE_KEYVAULT_URI'
              value: webhookVaultUri
            }
          ])
          probes: healthProbes
        }
      ]
      scale: {
        minReplicas: intakeScale.minReplicas
        maxReplicas: intakeScale.maxReplicas
        rules: [
          {
            name: 'http-concurrency'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ]
      }
    }
  }
}

// ---------------------------------------------------------------- salesforce-writer, one per country

resource writers 'Microsoft.App/containerApps@2024-03-01' = [for (writer, i) in identities.writers: {
  name: 'ca-${baseName}-sfwriter-${toLower(writer.country)}'
  location: location
  tags: union(tags, {
    country: writer.country
  })
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${writer.id}': {}
    }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      // No ingress: the writer only pulls from Service Bus.
      registries: [
        {
          server: registryServer
          identity: writer.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'salesforce-writer'
          image: '${registryServer}/salesforce-writer:${imageTag}'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv, [
            {
              name: 'AZURE_CLIENT_ID'
              value: writer.clientId
            }
            {
              name: 'WRITER_COUNTRY'
              value: writer.country
            }
            {
              name: 'WRITER_TOPIC'
              value: topicName
            }
            {
              name: 'WRITER_SUBSCRIPTION'
              value: writerSubscriptions[i].name
            }
            {
              name: 'WRITER_KEYVAULT_URI'
              value: salesforceVaultUri
            }
          ])
          probes: healthProbes
        }
      ]
      scale: {
        // Fixed and always warm: the 10-second batch window (§6.3) needs a running consumer, and a
        // crashed replica is restarted by the platform within seconds.
        minReplicas: writerReplicas
        maxReplicas: writerReplicas
      }
    }
  }
}]

// ---------------------------------------------------------------- payment-ops

resource ops 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'ca-${baseName}-ops'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identities.ops.id}': {}
    }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: internalIngress
      registries: [
        {
          server: registryServer
          identity: identities.ops.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'payment-ops'
          image: '${registryServer}/payment-ops:${imageTag}'
          resources: {
            cpu: json('0.25')
            memory: '0.5Gi'
          }
          env: concat(commonEnv, [
            {
              name: 'AZURE_CLIENT_ID'
              value: identities.ops.clientId
            }
            {
              name: 'OPS_TOPIC'
              value: topicName
            }
            {
              name: 'OPS_QUARANTINE_QUEUE'
              value: quarantineQueueName
            }
          ])
          probes: healthProbes
        }
      ]
      scale: {
        minReplicas: opsMinReplicas
        maxReplicas: 2
      }
    }
  }
}

output environmentId string = environment.id
output defaultDomain string = environment.properties.defaultDomain
output staticIp string = environment.properties.staticIp
output intakeFqdn string = intake.properties.configuration.ingress.fqdn
output opsFqdn string = ops.properties.configuration.ingress.fqdn
