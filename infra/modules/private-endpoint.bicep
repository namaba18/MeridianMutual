// A private endpoint for one Azure service, registered in its private DNS zone, so the service is
// reachable only from inside the VNet (public network access is disabled on every data service).

param name string
param location string
param subnetId string

@description('Resource id of the service the endpoint connects to.')
param targetResourceId string

@description('Sub-resource of the service, e.g. namespace, Sql, vault.')
param groupId string

param privateDnsZoneId string
param tags object

resource endpoint 'Microsoft.Network/privateEndpoints@2023-11-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    subnet: {
      id: subnetId
    }
    privateLinkServiceConnections: [
      {
        name: name
        properties: {
          privateLinkServiceId: targetResourceId
          groupIds: [
            groupId
          ]
        }
      }
    ]
  }
}

resource dnsGroup 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2023-11-01' = {
  parent: endpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [
      {
        name: groupId
        properties: {
          privateDnsZoneId: privateDnsZoneId
        }
      }
    ]
  }
}
