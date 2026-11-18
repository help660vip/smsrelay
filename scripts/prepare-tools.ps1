param([string]$Tools="E:\smsrelay\tools")
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
New-Item -ItemType Directory -Force -Path $Tools | Out-Null
$items=Get-Content "$PSScriptRoot\tools.lock.json" -Raw | ConvertFrom-Json
foreach($item in $items){
 $path=Join-Path $Tools $item.name
 if(!(Test-Path $path)){Invoke-WebRequest $item.url -OutFile $path}
 if((Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256){throw "Checksum mismatch: $($item.name)"}
}
if(!(Test-Path "$Tools\jdk")){Expand-Archive "$Tools\jdk.zip" -DestinationPath "$Tools\jdk"}
if(!(Test-Path "$Tools\sdk\android-33\android.jar")){Expand-Archive "$Tools\platform.zip" -DestinationPath "$Tools\sdk" -Force}
Write-Output "Verified build tools in $Tools"
