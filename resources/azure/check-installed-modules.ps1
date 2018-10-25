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
$modules = Invoke-RestMethod $checkModulesUrl -Method Get -Headers $headers -ErrorAction Stop
if($modules.Length -gt 0){
    Write-Output "check modules request done"
}
else
{
    Write-Output "No module's info returned"
    exit 1
}
Foreach($module in $modules)
{
    if($module.validationErrors.Length -gt 0){
        Write-Output $module.Name
        Write-Output $module.validationErrors
        exit 1
    }
}