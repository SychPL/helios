param(
    [ValidateSet('All', 'Upstream', 'JVM', 'Android')][string]$Mode = 'All',
    [string]$Jdk = '<user-home>/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2',
    [string]$Sdk = '<user-home>/AppData/Local/Android/Sdk'
)
$ErrorActionPreference = 'Stop'
$probeRoot = $PSScriptRoot
$workspaceRoot = Split-Path (Split-Path $probeRoot -Parent) -Parent
$upstreamRoot = Join-Path $probeRoot 'upstream'
$pin = ConvertFrom-StringData (Get-Content (Join-Path $probeRoot 'upstream.lock') -Raw)
if (-not (Test-Path (Join-Path $Jdk 'bin/java.exe'))) { throw 'Configured JDK missing' }
if (-not (Test-Path (Join-Path $Sdk 'platforms/android-35/android.jar'))) { throw 'Android SDK platform 35 missing' }
if (-not (Test-Path $upstreamRoot)) {
    & git clone --no-checkout $pin.repository $upstreamRoot
    if ($LASTEXITCODE -ne 0) { throw 'Public source clone failed' }
    & git -C $upstreamRoot checkout --detach $pin.revision
    if ($LASTEXITCODE -ne 0) { throw 'Pinned source checkout failed' }
}
$actual = & git -c "safe.directory=$($upstreamRoot.Replace('\','/'))" -C $upstreamRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actual -ne $pin.revision) { throw 'Upstream revision differs from upstream.lock' }
$dirty = & git -c "safe.directory=$($upstreamRoot.Replace('\','/'))" -C $upstreamRoot status --porcelain --untracked-files=no
if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Upstream tracked sources have modifications' }
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
$wrapper = Join-Path $workspaceRoot 'gradlew.bat'
if ($Mode -in @('All', 'Upstream')) {
    & $wrapper -p $upstreamRoot :sendspin-protocol:test :sendspin-protocol:jar --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Upstream verification failed' }
}
$tasks = @()
if ($Mode -in @('All', 'JVM')) { $tasks += ':jvm:test' }
if ($Mode -in @('All', 'Android')) { $tasks += ':android-probe:assembleDebug' }
if ($tasks.Count) {
    & $wrapper -p $probeRoot @tasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Compatibility verification failed' }
}
