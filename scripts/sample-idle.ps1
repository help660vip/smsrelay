param([Parameter(Mandatory)][string]$Serial,[string]$Tools='E:\smsrelay\tools',[string]$StateDir='/data/adb/smsrelay-test-live',[string]$Jar='/data/local/tmp/smsrelay-test.jar',[string]$Output)
$ErrorActionPreference='Stop'
$adb="$Tools\adb\adb.exe"
$statusText=& $adb -s $Serial shell "su -c 'CLASSPATH=$Jar app_process /system/bin dev.smsrelay.Main ctl $StateDir status'"
$status=$statusText | ConvertFrom-Json
if(!$status.running){throw 'service not running'}
$corePid=[int]$status.pid
$coreStat=& $adb -s $Serial shell "su -c 'cat /proc/$corePid/stat'"
$fields=($coreStat.Substring($coreStat.LastIndexOf(')')+2)) -split '\s+'
$supervisorPid=[int]$fields[1]
$superStat=& $adb -s $Serial shell "su -c 'cat /proc/$supervisorPid/stat'"
$sf=($superStat.Substring($superStat.LastIndexOf(')')+2)) -split '\s+'
$remote="su -c 'for t in /proc/$corePid/task/*; do cat `"`$t/comm`" `"`$t/wchan`"; echo; done; dumpsys power | grep smsrelay:work'"
$threads=& $adb -s $Serial shell $remote
$result=[ordered]@{time=[DateTimeOffset]::UtcNow.ToString('o');status=$status;core_cpu_ticks=([long]$fields[11]+[long]$fields[12]);supervisor_cpu_ticks=([long]$sf[11]+[long]$sf[12]);core_start=$fields[19];supervisor_start=$sf[19];threads=$threads}
$json=$result | ConvertTo-Json -Depth 10
if($Output){[IO.File]::WriteAllText($Output,$json)}else{$json}
