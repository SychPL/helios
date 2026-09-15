param([string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk")
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$cmake = "$Sdk\cmake\3.31.6\bin\cmake.exe"
$nativeRoot = Join-Path $taskRoot '.local\microwakeword'
& $cmake -S "$nativeRoot\microwakeword\src\main\cpp" -B "$nativeRoot\build-armv7" -G Ninja "-DCMAKE_MAKE_PROGRAM=$Sdk/cmake/3.31.6/bin/ninja.exe" "-DCMAKE_TOOLCHAIN_FILE=$Sdk/ndk/27.1.12297006/build/cmake/android.toolchain.cmake" -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'Native configuration failed' }
& $cmake --build "$nativeRoot\build-armv7" --target microwakeword -j 6
if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
