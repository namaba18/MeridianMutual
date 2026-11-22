// Network for one environment: a VNet with a subnet for the Container Apps environment and a
// subnet for private endpoints, plus the private DNS zones those endpoints need.
// No service in this design accepts traffic on a public endpoint, except telemetry ingestion into
// Application Insights (see monitoring.bicep). The only internet-facing entry is the API Manager
// (SOLUTION_DESIGN §1.2), which reaches this VNet over private connectivity (assumption 8).

@description('Base name used for every resource, e.g. pay-dev.')
param baseName string

param location string

@description('Address space of the VNet, e.g. 10.40.0.0/22. Must not overlap other Meridian networks.')
param addressPrefix string

@description('Subnet for the Container Apps environment (at least /27, delegated to Microsoft.App/environments).')
param containerAppsSubnetPrefix string

@description('Subnet for private endpoints of Service Bus, Cosmos DB and Key Vault.')
param privateEndpointsSubnetPrefix string

param tags object

// Private DNS zones for the private endpoints. In a hub-and-spoke landing zone these usually live
// in the hub; here they are created per environment and linked to this VNet.
var privateDnsZones = [
  {
    service: 'serviceBus'
    zone: 'privatelink.servicebus.windows.net'
  }
  {
    service: 'cosmos'
    zone: 'privatelink.documents.azure.com'
  }
  {
    service: 'keyVault'
    zone: 'privatelink.vaultcore.azure.net'
  }
]

resource vnet 'Microsoft.Network/virtualNetworks@2023-11-01' = {
  name: 'vnet-${baseName}'
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [
        addressPrefix
      ]
    }
    subnets: [
      {
        name: 'snet-container-apps'
        properties: {
          addressPrefix: containerAppsSubnetPrefix
          delegations: [
            {
              name: 'container-apps'
              properties: {
                serviceName: 'Microsoft.App/environments'
              }
            }
          ]
        }
      }
      {
        name: 'snet-private-endpoints'
        properties: {
          addressPrefix: privateEndpointsSubnetPrefix
          privateEndpointNetworkPolicies: 'Enabled'
        }
      }
    ]
  }
}

resource dnsZones 'Microsoft.Network/privateDnsZones@2020-06-01' = [for zone in privateDnsZones: {
  name: zone.zone
  location: 'global'
  tags: tags
}]

resource dnsZoneLinks 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = [for (zone, i) in privateDnsZones: {
  parent: dnsZones[i]
  name: 'link-${baseName}'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: {
      id: vnet.id
    }
  }
}]

output vnetId string = vnet.id
output containerAppsSubnetId string = vnet.properties.subnets[0].id
output privateEndpointsSubnetId string = vnet.properties.subnets[1].id

@description('Private DNS zone ids, by service: serviceBus, cosmos, keyVault.')
output privateDnsZoneIds object = toObject(privateDnsZones, z => z.service, z => resourceId('Microsoft.Network/privateDnsZones', z.zone))
