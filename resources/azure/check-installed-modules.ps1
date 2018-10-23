Param(
    [parameter(Mandatory=$true)]
    $apiurl,
    $hmacAppId,
    $hmacSecret
)

. $PSScriptRoot\utilities.ps1

if ([string]::IsNullOrWhiteSpace($hmacAppId))
{
    $hmacAppId = "${env:HMAC_APP_ID}"
}

if ([string]::IsNullOrWhiteSpace($hmacSecret))
{
    $hmacSecret = "${env:HMAC_SECRET}"
}

$checkModulesUrl = "$apiurl/api/platform/modules"

# Initiate sample data installation
$headerValue = Create-Authorization $hmacAppId $hmacSecret
$headers = @{}
$headers.Add("Authorization", $headerValue)
$modules = Invoke-WebRequest $checkModulesUrl -Method Get -Headers $headers -ErrorAction Stop
Write-Output "check modules request done"
Write-Output $modules
Write-Output "end of raw data"
Write-Output $modules.GetType().Fullname
Foreach($module in $modules)
{
    Write-Output $module.validationErrors
}