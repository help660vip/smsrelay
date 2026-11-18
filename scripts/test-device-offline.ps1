param([Parameter(Mandatory)][string]$Serial,[string]$Tools='E:\smsrelay\tools')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb="$Tools\adb\adb.exe"
$run='smsrelay-test-'+[Guid]::NewGuid().ToString('N')
$stateDir="/data/local/tmp/$run"
$config=Get-Content "$root\module\config\config.json" -Raw | ConvertFrom-Json
$config.enabled=$true;$config.wecom.enabled=$true;$config.smtp.enabled=$true
$config.wecom.webhook='https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=LOCAL-TEST'
$config | ConvertTo-Json -Depth 8 | Set-Content "$root\build\offline-test.json" -Encoding utf8
& $adb -s $Serial push "$root\build\smsrelay.jar" /data/local/tmp/smsrelay-offline-test.jar
if($LASTEXITCODE){throw 'adb push failed'}
& $adb -s $Serial shell "su -c 'umask 077; mkdir -p $stateDir'"
Get-Content "$root\build\offline-test.json" -Raw | & $adb -s $Serial shell "su -c 'umask 077; cat > $stateDir/config.json'"
& $adb -s $Serial shell "su -c 'CLASSPATH=/data/local/tmp/smsrelay-offline-test.jar app_process /system/bin dev.smsrelay.Main selftest $stateDir 2>$stateDir/stderr.txt'"
if($LASTEXITCODE){throw "offline tests failed; isolated diagnostics: $stateDir/stderr.txt"}
Write-Output "Isolated data retained at $stateDir; no real SMS DB or network used"
