// Observability for one environment (NFR7). The Log Analytics workspace already exists at platform
// level (SCENARIO.md), so it is only referenced. This module adds the Application Insights
// component the services send traces and metrics to, linked to that workspace.

param baseName string
param location string

@description('Resource id of the platform Log Analytics workspace.')
param logAnalyticsWorkspaceId string

param tags object

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: 'appi-${baseName}'
  location: location
  tags: tags
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalyticsWorkspaceId
    IngestionMode: 'LogAnalytics'
    // Telemetry is accepted only with Entra ID tokens (managed identities), not the instrumentation key.
    DisableLocalAuth: true
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

output appInsightsId string = appInsights.id
output appInsightsConnectionString string = appInsights.properties.ConnectionString
