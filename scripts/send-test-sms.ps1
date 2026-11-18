param([string]$PrivateDir='E:\smsrelay\private',[string]$Body,[string]$From='SMSRELAY_TEST')
$ErrorActionPreference='Stop'
$c=Get-Content "$PrivateDir\sms-api.local.json" -Raw | ConvertFrom-Json
$countFile="$PrivateDir\sms-request-count.txt"
$count=0
if(Test-Path $countFile){$count=[int](Get-Content $countFile -Raw)}
if($count -ge 100){throw 'SMS API budget exhausted'}
if(!$Body.Contains('SMSRELAY_TEST')){throw 'Synthetic test marker required'}
$count++
[IO.File]::WriteAllText($countFile,[string]$count)
$u=[UriBuilder]$c.endpoint
$u.Query='apikey='+[Uri]::EscapeDataString($c.apikey)+'&from='+[Uri]::EscapeDataString($From)+'&body='+[Uri]::EscapeDataString($Body)
$client=[Net.Http.HttpClient]::new(); $client.Timeout=[TimeSpan]::FromSeconds(25)
try {
 $r=$client.GetAsync($u.Uri).GetAwaiter().GetResult()
 $response=$r.Content.ReadAsStringAsync().GetAwaiter().GetResult()
 [IO.File]::WriteAllText("$PrivateDir\sms-api-last-response.txt",$response)
 Write-Output "SMS_API attempt=$count http=$([int]$r.StatusCode) response_saved_privately=true"
} catch { Write-Output "SMS_API attempt=$count transport_failed=$($_.Exception.GetType().Name)" } finally {$client.Dispose()}
