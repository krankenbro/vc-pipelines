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

     # Initiate modules installation
     $headerValue = Create-Authorization $hmacAppId $hmacSecret
     $headers = @{}
     $headers.Add("Authorization", $headerValue)
     #Write-Output Invoke-RestMethod $moduleInstallUrl -Method Post -InFile $moduleZipArchievePath -Headers $headers -ContentType 'multipart/form-data' -ErrorAction Stop
     #$moduleInstallResult = Invoke-RestMethod $moduleInstallUrl -Method Post -InFile $moduleZipArchievePath -ContentType 'multipart/form-data' -Headers $headers -ErrorAction Stop
     $moduleInstallResult = Invoke-MultipartFormDataUpload -InFile $moduleZipArchievePath -Uri $moduleInstallUrl -Authorization $headerValue
     Write-Output $moduleInstallResult

