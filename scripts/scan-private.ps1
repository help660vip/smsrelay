param([Parameter(Mandatory)][string]$PrivateDir,[string]$Archive)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=Get-Content "$PrivateDir\config.local.json" -Raw | ConvertFrom-Json
$api=Get-Content "$PrivateDir\sms-api.local.json" -Raw | ConvertFrom-Json
$secrets=@($config.smtp.username,$config.smtp.password,$config.smtp.from)+@($config.smtp.to)+@($api.apikey,([Uri]$api.endpoint).Segments[-1])
$hook=[Uri]$config.wecom.webhook
$secrets+=($hook.Query -replace '^\?key=','')
$secrets=$secrets | Where-Object {$_ -and $_.Length -ge 7} | Select-Object -Unique
$script:findings=[Collections.Generic.List[string]]::new()
function Check-Bytes([byte[]]$Bytes,[string]$Label){
 $text=[Text.Encoding]::UTF8.GetString($Bytes)
 foreach($secret in $secrets){foreach($candidate in @($secret,[Uri]::EscapeDataString($secret),[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($secret)))){if($text.Contains($candidate)){$script:findings.Add($Label);return}}}
 if($Label -match '\.(zip|jar)$'){
  $ms=[IO.MemoryStream]::new($Bytes,$false)
  try{$zip=[IO.Compression.ZipArchive]::new($ms,[IO.Compression.ZipArchiveMode]::Read);try{foreach($entry in $zip.Entries){if($entry.FullName.EndsWith('/')){continue};$em=[IO.MemoryStream]::new();$es=$entry.Open();try{$es.CopyTo($em);Check-Bytes $em.ToArray() "$Label/$($entry.FullName)"}finally{$es.Dispose();$em.Dispose()}}}finally{$zip.Dispose()}}finally{$ms.Dispose()}
 }
}
Add-Type -AssemblyName System.IO.Compression
$files=& git -C $root ls-files --cached --others --exclude-standard
foreach($name in $files){$path=Join-Path $root $name;if(Test-Path -LiteralPath $path -PathType Leaf){Check-Bytes ([IO.File]::ReadAllBytes($path)) $name}}
$commits=& git -C $root rev-list --all 2>$null
foreach($commit in $commits){$names=& git -C $root ls-tree -r --name-only $commit;foreach($name in $names){$content=& git -C $root show "${commit}:$name" 2>$null;Check-Bytes ([Text.Encoding]::UTF8.GetBytes(($content -join "`n"))) "history:$commit/$name"}}
if($Archive){Check-Bytes ([IO.File]::ReadAllBytes($Archive)) ([IO.Path]::GetFileName($Archive))}
if($script:findings.Count){Write-Output 'SECRET_SCAN_FAILED paths only:';$script:findings | Select-Object -Unique;throw 'Private data detected'}
Write-Output "SECRET_SCAN_OK source_files=$($files.Count) commits=$($commits.Count) archive=$([bool]$Archive); secret values never printed"
