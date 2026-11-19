param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$Tools='E:\smsrelay\tools',
    [ValidateRange(0,45)][int]$IdleSeconds=0
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$adb="$Tools\adb\adb.exe"
$tag='smsrelay-test-web-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$stateDir="/data/local/tmp/$tag"
$dex="/data/local/tmp/$tag.jar"
$entry="CLASSPATH=$dex app_process /system/bin dev.smsrelay.Main"
$title='SMSRELAY_SYNTHETIC_NOTICE'
$body='SMSRELAY_SYNTHETIC_CONTENT'

function Root([string]$Command) {
    $lines=& $adb -s $Serial shell "su -c '$Command'"
    if($LASTEXITCODE){throw 'K40 test command failed'}
    return ($lines -join [Environment]::NewLine)
}
function Web([hashtable]$Request) {
    $json=$Request | ConvertTo-Json -Depth 12 -Compress
    $token=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json)).TrimEnd('=').Replace('+','-').Replace('/','_')
    $lines=$token | & $adb -s $Serial shell "su -c '$entry web $stateDir'"
    if($LASTEXITCODE){throw 'WebUI command failed'}
    return (($lines -join [Environment]::NewLine) | ConvertFrom-Json)
}
function Verify([bool]$Condition,[string]$Message) {
    if(!$Condition){throw $Message}
}

& $adb -s $Serial push "$root\build\smsrelay.jar" $dex | Out-Null
if($LASTEXITCODE){throw 'cannot stage DEX'}
Root "umask 077; mkdir $stateDir" | Out-Null
$template=Get-Content "$root\module\config\config.json" -Raw -Encoding UTF8
$template.Replace('REPLACE-ME','LOCAL-TEST') | & $adb -s $Serial shell "su -c 'umask 077; cat > $stateDir/config.json'"
if($LASTEXITCODE){throw 'cannot stage isolated configuration'}
try {
    $started=Root "$entry ctl $stateDir start" | ConvertFrom-Json
    Verify ($started.observer_registered -eq $true) 'SMS observer unavailable'
    Verify ((Root "$entry ctl $stateDir test-offline").Contains('unavailable=true')) 'network isolation failed'
    $s=Web @{op='settings'}
    Verify ($s.revision -and !$s.smtp.password -and !$s.wecom.webhook) 'credential readback'
    $apps=Web @{op='apps'}
    Verify (($apps.apps | Where-Object {$_.pkg -eq 'com.android.shell' -and $_.label}).Count -eq 1) 'system app label missing'
    $save=Web @{op='save';revision=$s.revision;changes=@{
        'wecom.enabled'=$true
        'smtp.enabled'=$true
        'routing.call.enabled'=$true
        'routing.notification.enabled'=$true
        'routing.notification.wecom'=$true
        'routing.notification.apps'=@('com.android.shell')
    }}
    Verify (!$save.error -and !$save.confirmation) 'routing could not be applied'

    $ready=$null
    for($i=0;$i -lt 12;$i++){
        $ready=Web @{op='status'}
        if($ready.health.call.listener -eq 'ready' -and $ready.health.notification.listener -eq 'ready'){break}
        Start-Sleep -Milliseconds 250
    }
    Verify ($ready.health.call.listener -eq 'ready' -and $ready.health.notification.listener -eq 'ready') 'new listeners did not become ready'
    Verify ($ready.health.call.ready_subscriptions -gt 0 -and $ready.health.call.ready_subscriptions -eq $ready.health.call.watched_subscriptions) 'subscription callbacks incomplete'
    if($IdleSeconds -gt 0){
        $before=& "$PSScriptRoot\sample-idle.ps1" -Serial $Serial -Tools $Tools -StateDir $stateDir -Jar $dex | ConvertFrom-Json
        Start-Sleep -Seconds $IdleSeconds
        $after=& "$PSScriptRoot\sample-idle.ps1" -Serial $Serial -Tools $Tools -StateDir $stateDir -Jar $dex | ConvertFrom-Json
        $queries=$after.status.queries-$before.status.queries
        $sends=$after.status.sends-$before.status.sends
        $cpu=$after.core_cpu_ticks-$before.core_cpu_ticks
        $supervisorCpu=$after.supervisor_cpu_ticks-$before.supervisor_cpu_ticks
        Verify ($before.status.instance -eq $after.status.instance) 'idle service instance changed'
        Verify ($queries -eq 0 -and $sends -eq 0) 'idle sample included system activity'
        Verify (!$before.status.network_callback -and !$after.status.network_callback -and !$before.status.wakelock -and !$after.status.wakelock) 'idle state retained network or wake resources'
        Write-Output "WEBUI_IDLE_SECONDS=$IdleSeconds query_delta=$queries sends_delta=$sends core_tick_delta=$cpu supervisor_tick_delta=$supervisorCpu network_callback=false wakelock=false"
    }
    $preview=@{op='preview';kind='notification';pkg='com.android.shell';title=$title;body=$body}
    $choice=Web $preview
    Verify ($choice.decision -eq 'queued' -and $choice.channels.Count -eq 2 -and !$choice.id) 'dry run routing did not match'
    Verify ((Web @{op='history';search=$title}).rows.Count -eq 0) 'preview wrote to history'

    & $adb -s $Serial shell "cmd notification post -t $title $tag $body" | Out-Null
    if($LASTEXITCODE){throw 'synthetic notification failed'}
    $rows=@()
    for($i=0;$i -lt 12;$i++){
        $rows=(Web @{op='history';kind='notification';search=$title}).rows
        if($rows.Count -gt 0){break}
        Start-Sleep -Milliseconds 250
    }
    Verify ($rows.Count -eq 1 -and !$rows[0].synthetic) 'listener did not capture the system-posted test notification'
    $id=$rows[0].id
    $detail=Web @{op='detail';id=$id}
    Verify ($detail.body -eq $body -and $detail.tasks.Count -eq 2 -and $detail.steps.Count -ge 3) 'history content, timing or independent routes missing'
    Verify (($detail.tasks | Where-Object {$_.state -eq 'PENDING'}).Count -eq 2) 'offline pending tasks are not intact'
    $redacted=(Web @{op='share';id=$id}).text
    Verify ($redacted -and !$redacted.Contains($body) -and !$redacted.Contains($title) -and !$redacted.Contains('com.android.shell')) 'redacted export leaked event fields'
    $preview.op='test'
    $preview.confirmed=$true
    $sample=Web $preview
    $mock=Web @{op='history';kind='notification';search=$title}
    Verify ($sample.id -gt $id -and ($mock.rows | Where-Object synthetic).Count -eq 1) 'synthetic route test missing from history'

    Verify ((Root "$entry ctl $stateDir test-smtp") -eq 'test_queued') 'Magisk direct channel test failed'
    $direct=(Web @{op='history';search='SMSRELAY_TEST'}).rows | Where-Object {$_.kind -eq 'test'}
    Verify ($direct.Count -eq 1) 'direct channel test missing from history'
    $directTask=(Web @{op='detail';id=$direct[0].id}).tasks[0]
    Verify ($directTask.channel -eq 'smtp' -and !$directTask.held) 'direct test blocked while SMS route was disabled'

    $s=Web @{op='settings'}
    $changed=Web @{op='save';revision=$s.revision;changes=@{'smtp.to'=@('changed@example.invalid')}}
    Verify ($changed.confirmation -eq 'hold_pending') 'changed destination did not require confirmation'
    $updated=Root "cat $stateDir/config.json"
    Verify ($updated.Contains('//') -and $updated.Contains('recipient@example.invalid')) 'JSONC comments or original recipient were lost'
    Write-Output "WEBUI_DEVICE_OK sms_observer=ready ring_listener=ready subscriptions=$($ready.health.call.ready_subscriptions) notification_listener=ready dry_run=clean real_system_notification=1 routes=2 offline_pending=2 full_history=ok redacted_share=ok route_test=ok direct_channel_test=ok change_guard=ok comments=kept"
}
finally {
    Root "$entry ctl $stateDir stop" | Out-Null
    & $adb -s $Serial shell "cmd notification cancel $tag" 2>$null | Out-Null
}
