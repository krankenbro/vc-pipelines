Param(  
  	[parameter(Mandatory=$true)]
      $apiurl,
      [parameter(Mandatory=$true)]
      $moduleZipArchievePath,
    [parameter(Mandatory=$true)]
    $moduleId,
    [parameter(Mandatory=$true)]
    $platformContainer,
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

docker cp $moduleZipArchievePath $platformContainer:C:\
docker exec $platformContainer powershell -Command "Remove-Item C:\vc-platform\Modules\$moduleId -Force -Recurse"
docker exec $platformContainer powershell -Command "Expand-Archive -Path C:\*.zip -DestinationPath C:\vc-platform\Modules\$moduleId"

#restart platform
$restartUrl = "$apiurl/api/platform/modules/restart"

$headerValue = Create-Authorization $hmacAppId $hmacSecret
$headers = @{}
$headers.Add("Authorization", $headerValue)

Write-Output "Restarting website"
$moduleState = Invoke-RestMethod "$restartUrl" -Method Post -ContentType "application/json" -Headers $headers
Write-Output $moduleState