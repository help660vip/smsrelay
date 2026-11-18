param([Parameter(Mandatory)][string]$Serial,[string]$Tools='E:\smsrelay\tools')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb="$Tools\adb\adb.exe"
$run='smsrelay-test-'+[Guid]::NewGuid().ToString('N')
$stateDir="/data/local/tmp/$run"
$template=Get-Content "$root\module\config\config.json" -Raw -Encoding UTF8
$config=$template | ConvertFrom-Json
$config.enabled=$true;$config.wecom.enabled=$true;$config.smtp.enabled=$true
$config.wecom.webhook='https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=LOCAL-TEST'
$config | ConvertTo-Json -Depth 8 | Set-Content "$root\build\offline-test.json" -Encoding utf8
& $adb -s $Serial push "$root\build\smsrelay.jar" /data/local/tmp/smsrelay-offline-test.jar
if($LASTEXITCODE){throw 'adb push failed'}
& $adb -s $Serial shell "su -c 'umask 077; mkdir -p $stateDir'"
if($LASTEXITCODE){throw 'cannot create isolated state directory'}
$template | & $adb -s $Serial shell "su -c 'umask 077; cat > $stateDir/config.json'"
if($LASTEXITCODE){throw 'cannot stage commented config'}
$check=& $adb -s $Serial shell "su -c 'CLASSPATH=/data/local/tmp/smsrelay-offline-test.jar app_process /system/bin dev.smsrelay.Main ctl $stateDir check'"
if($LASTEXITCODE -or $check -notcontains 'config_valid; service_not_running'){throw 'commented config failed device validation'}
Write-Output 'CONFIG_TEMPLATE_OK commented config accepted by Android'
Get-Content "$root\build\offline-test.json" -Raw | & $adb -s $Serial shell "su -c 'umask 077; cat > $stateDir/config.json'"
if($LASTEXITCODE){throw 'cannot stage plain JSON config'}
& $adb -s $Serial shell "su -c 'CLASSPATH=/data/local/tmp/smsrelay-offline-test.jar app_process /system/bin dev.smsrelay.Main selftest $stateDir 2>$stateDir/stderr.txt'"
if($LASTEXITCODE){throw "offline tests failed; isolated diagnostics: $stateDir/stderr.txt"}
Write-Output "Isolated data retained at $stateDir; no real SMS DB or network used"
