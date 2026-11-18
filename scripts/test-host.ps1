param([string]$Tools="E:\smsrelay\tools")
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$jdk=(Get-ChildItem "$Tools\jdk" -Directory | Select-Object -First 1).FullName
New-Item -ItemType Directory -Force -Path "$root\build\host-tests" | Out-Null
& "$jdk\bin\javac.exe" --release 8 -encoding UTF-8 -d "$root\build\host-tests" "$root\src\dev\smsrelay\Rules.java" "$root\tests\RulesTest.java"
if($LASTEXITCODE){throw 'host test compilation failed'}
& "$jdk\bin\java.exe" -cp "$root\build\host-tests" RulesTest
if($LASTEXITCODE){throw 'host tests failed'}
