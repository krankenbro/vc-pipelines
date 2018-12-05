Param(
    [parameter(Mandatory = $true)]
    $themeZip,
    [parameter(Mandatory = $true)]
    $platformContainer
)

Write-Output $platformContainer
Write-Output $themeZip
docker cp $themeZip ${platformContainer}:/vc-platform/
docker exec $platformContainer powershell -Command "Remove-Item C:\vc-platform\App_Data\cms-content\Themes\Electronics\default\ -Force -Recurse"
docker exec $platformContainer powershell -Command "Expand-Archive -Path C:\vc-platform\theme.zip -DestinationPath C:\vc-platform\App_Data\cms-content\Themes\Electronics\"
