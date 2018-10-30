Param(  
  	[parameter(Mandatory=$true)]
      $apiurl,
      [parameter(Mandatory=$true)]
      $moduleZipArchievePath,
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

     # Initialize paths used by the script
     $moduleInstallUrl = "$apiurl/api/platform/modules/localstorage"
    $restartUrl = "$apiurl/api/platform/modules/restart"

     # Initiate modules installation
     $headerValue = Create-Authorization $hmacAppId $hmacSecret
     $headers = @{}
     $headers.Add("Authorization", $headerValue)

     $moduleInstallResult = Invoke-MultipartFormDataUpload -InFile $moduleZipArchievePath -Uri $moduleInstallUrl -Authorization $headerValue
     Write-Output $moduleInstallResult
    $moduleState = Invoke-RestMethod "$restartUrl" -Method Post -ContentType "application/json" -Headers $headers
    Start-Sleep -s 5



