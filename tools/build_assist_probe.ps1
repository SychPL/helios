param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Jdk = 'C:\Program Files\Android\Android Studio\jbr'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$buildDir = Join-Path $taskRoot '.local\assist'
New-Item -ItemType Directory -Force "$buildDir\classes","$buildDir\dex" | Out-Null
$jars = @((Get-ChildItem -LiteralPath $buildDir -Filter '*.jar').FullName)
$classpath = (@("$Sdk\platforms\android-34\android.jar") + $jars) -join ';'
& "$Jdk\bin\javac.exe" -source 8 -target 8 -classpath $classpath -d "$buildDir\classes" "$taskRoot\probes\assist\AssistButtonProbe.java"
if ($LASTEXITCODE -ne 0) { throw 'Assist Java compilation failed' }
$classes = @((Get-ChildItem -LiteralPath "$buildDir\classes" -Filter '*.class' -Recurse).FullName)
$env:JAVA_HOME = $Jdk
& "$Sdk\build-tools\36.0.0\d8.bat" --min-api 29 --lib "$Sdk\platforms\android-34\android.jar" --output "$buildDir\dex" @classes @jars
if ($LASTEXITCODE -ne 0) { throw 'Assist DEX compilation failed' }
