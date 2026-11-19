param([string]$Tools = "E:\smsrelay\tools", [switch]$ProbeOnly)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$jdk=(Get-ChildItem "$Tools\jdk" -Directory | Select-Object -First 1).FullName
$android=(Get-ChildItem "$Tools\sdk" -Filter android.jar -Recurse | Select-Object -First 1).FullName
if(!$android -or !$jdk){throw 'Run scripts/prepare-tools.ps1 first'}
$run=Join-Path "$root\build" ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path "$run\classes","$run\dex","$root\dist" | Out-Null
$files=Get-ChildItem "$root\src" -Filter '*.java' -Recurse | ForEach-Object FullName
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath "$android;$Tools\android-mail.jar;$Tools\android-activation.jar" -d "$run\classes" $files
if($LASTEXITCODE){throw 'javac failed'}
& "$jdk\bin\jar.exe" cf "$run\classes.jar" -C "$run\classes" .
& "$jdk\bin\java.exe" -cp "$Tools\r8.jar" com.android.tools.r8.D8 --min-api 25 --lib $android --output "$run\dex" "$run\classes.jar" "$Tools\android-mail.jar" "$Tools\android-activation.jar"
if($LASTEXITCODE){throw 'D8 failed'}
Add-Type -AssemblyName System.IO.Compression
$stamp=[DateTimeOffset]::new(2026,1,1,0,0,0,[TimeSpan]::Zero)
function Add-ZipBytes($zip,[string]$name,[byte[]]$bytes){
 $entry=$zip.CreateEntry($name,[IO.Compression.CompressionLevel]::Optimal);$entry.LastWriteTime=$stamp
 $stream=$entry.Open();try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
}
$jar="$root\build\smsrelay.jar"
$stream=[IO.File]::Open($jar,[IO.FileMode]::Create);$zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
try{
 Get-ChildItem "$run\dex" -Filter '*.dex' | Sort-Object Name | ForEach-Object {Add-ZipBytes $zip $_.Name ([IO.File]::ReadAllBytes($_.FullName))}
 $seen=@{}
 foreach($dependency in @('android-mail','android-activation')){
  $in=[IO.Compression.ZipFile]::OpenRead("$Tools\$dependency.jar")
  try{foreach($entry in ($in.Entries | Sort-Object FullName)){
   if($entry.FullName.EndsWith('/') -or $entry.FullName.EndsWith('.class') -or $entry.FullName -match 'META-INF/(MANIFEST.MF|.*\.(SF|RSA|DSA))$'){continue}
   $name=$entry.FullName
   if($name -match '(?i)(LICENSE|NOTICE|COPYING)'){$name="third-party/$dependency/$name"}
   if($seen.ContainsKey($name)){continue};$seen[$name]=$true
   $mem=[IO.MemoryStream]::new();$src=$entry.Open();try{$src.CopyTo($mem);Add-ZipBytes $zip $name $mem.ToArray()}finally{$src.Dispose();$mem.Dispose()}
  }}finally{$in.Dispose()}
 }
}finally{$zip.Dispose();$stream.Dispose()}
if($ProbeOnly){Write-Output "Built $jar";exit}
$version=((Get-Content "$root\module\module.prop" | Where-Object {$_ -match '^version='}) -split '=',2)[1]
$output="$root\dist\smsrelay-$version.zip"
$stream=[IO.File]::Open($output,[IO.FileMode]::Create);$zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
try{
 $whitelist=@('module.prop','service.sh','action.sh','smsrelay','uninstall.sh','customize.sh','skip_mount','config/config.json','webctl','webroot/index.html')
 foreach($name in $whitelist){$path=Join-Path "$root\module" $name;if($name -eq 'skip_mount'){Add-ZipBytes $zip $name ([byte[]]@())}else{$text=[IO.File]::ReadAllText($path).Replace("`r",'');Add-ZipBytes $zip $name ([Text.Encoding]::UTF8.GetBytes($text))}}
 Add-ZipBytes $zip 'smsrelay.jar' ([IO.File]::ReadAllBytes($jar))
 foreach($name in @('LICENSE','THIRD_PARTY_NOTICES.md')){Add-ZipBytes $zip $name ([IO.File]::ReadAllBytes("$root\$name"))}
}finally{$zip.Dispose();$stream.Dispose()}
$sha=(Get-FileHash $output -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$output.sha256","$sha  smsrelay-$version.zip`n")
Write-Output "$output SHA256=$sha"
