param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Jdk = 'C:\Program Files\Android\Android Studio\jbr'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$payloadDir = Join-Path $taskRoot '.local\microwakeword\payload'
New-Item -ItemType Directory -Force $payloadDir | Out-Null
$javaSources = @(
    (Join-Path $taskRoot 'probes\wakeword\WakeWordProbe.java'),
    (Join-Path $taskRoot 'probes\wakeword\io\homeassistant\companion\android\microwakeword\MicroWakeWord.java')
)
& "$Jdk\bin\javac.exe" -source 8 -target 8 -classpath "$Sdk\platforms\android-34\android.jar" @javaSources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$env:JAVA_HOME = $Jdk
$classFiles = @($javaSources | ForEach-Object { [IO.Path]::ChangeExtension($_, '.class') })
& "$Sdk\build-tools\34.0.0\d8.bat" --lib "$Sdk\platforms\android-34\android.jar" --output $payloadDir @classFiles
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
