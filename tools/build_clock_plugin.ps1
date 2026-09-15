param([Parameter(Mandatory=$true)][string]$Source,[Parameter(Mandatory=$true)][string]$Name)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$sdk="$env:LOCALAPPDATA\Android\Sdk"
$jdk='C:\Program Files\Android\Android Studio\jbr'
$out=Join-Path $taskRoot ".local\clock-plugins\$Name"
New-Item -ItemType Directory -Force "$out\classes","$out\dex" | Out-Null
& "$jdk\bin\javac.exe" -source 8 -target 8 -classpath "$sdk\platforms\android-34\android.jar" -d "$out\classes" $Source
if($LASTEXITCODE -ne 0){throw 'Plugin compilation failed'}
$classes=@((Get-ChildItem -LiteralPath "$out\classes" -Filter '*.class' -Recurse).FullName)
$env:JAVA_HOME=$jdk
& "$sdk\build-tools\36.0.0\d8.bat" --min-api 29 --lib "$sdk\platforms\android-34\android.jar" --output "$out\dex" @classes
if($LASTEXITCODE -ne 0){throw 'Plugin DEX build failed'}
