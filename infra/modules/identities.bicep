// One user-assigned managed identity per workload, and one per country for salesforce-writer, so
// each identity can be granted exactly what its workload needs and nothing else (NFR6).
// User-assigned (not system-assigned) so role assignments exist before the apps are created.

param baseName string
param location string

@description('Country codes served, e.g. [ "MX", "CO", "CL" ].')
param countryCodes array

param tags object

resource intake 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${baseName}-intake'
  location: location
  tags: tags
}

resource writers 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = [for code in countryCodes: {
  name: 'id-${baseName}-sfwriter-${toLower(code)}'
  location: location
  tags: union(tags, {
    country: code
  })
}]

resource ops 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${baseName}-ops'
  location: location
  tags: tags
}

output intake object = {
  id: intake.id
  principalId: intake.properties.principalId
  clientId: intake.properties.clientId
}

@description('One entry per country, in the same order as countryCodes.')
output writers array = [for (code, i) in countryCodes: {
  country: code
  id: writers[i].id
  principalId: writers[i].properties.principalId
  clientId: writers[i].properties.clientId
}]

output ops object = {
  id: ops.id
  principalId: ops.properties.principalId
  clientId: ops.properties.clientId
}
