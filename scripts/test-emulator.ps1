param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$names = @('one','two','three','four','five','six','seven','eight')
if (-not $Serial.StartsWith('emulator-')) { throw 'Only the existing emulator is allowed.' }
$avd = & $adb -s $Serial emu avd name
if (($avd -join "`n") -notmatch '(?m)^BILI_TV_API_34\s*$') { throw 'Target is not BILI_TV_API_34.' }
foreach ($name in $names) {
    $installed = & $adb -s $Serial shell pm path "io.lumatv.fixture.$name"
    if ($installed -match '^package:') { throw "Existing fixture package found: io.lumatv.fixture.$name. Refusing to replace it." }
}
$previousGradle = $env:GRADLE_USER_HOME
$previousSdk = $env:ANDROID_HOME
$installedFixtures = @()
Push-Location $root
try {
    & .\scripts\build.ps1
    $env:GRADLE_USER_HOME = Join-Path $root '.gradle-user'
    $env:ANDROID_HOME = $sdk
    New-Item -ItemType Directory -Force artifacts | Out-Null
    & .\gradlew.bat :fixture:assembleDebug --offline --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Fixture build failed.' }
    Copy-Item -LiteralPath fixture\build\outputs\apk\eight\debug\fixture-eight-debug.apk -Destination artifacts\fixture-eight-v1.apk
    & .\gradlew.bat :fixture:assembleEightDebug -PfixtureVersion=2 --offline --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Update fixture build failed.' }
    & .\scripts\run-emulator.ps1 -Serial $Serial
    & $adb -s $Serial install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
    foreach ($name in $names[0..6]) {
        & $adb -s $Serial install "fixture\build\outputs\apk\$name\debug\fixture-$name-debug.apk"
        if ($LASTEXITCODE -ne 0) { throw "Fixture installation failed: $name" }
        $installedFixtures += $name
    }
    # The suite installs/removes only this prechecked new package during the lifecycle case.
    $installedFixtures += 'eight'
    & $adb -s $Serial push artifacts\fixture-eight-v1.apk /data/local/tmp/luma-fixture-eight.apk
    & $adb -s $Serial push fixture\build\outputs\apk\eight\debug\fixture-eight-debug.apk /data/local/tmp/luma-fixture-eight-update.apk
    $output = & $adb -s $Serial shell am instrument -w -r -e class io.lumatv.launcher.RemoteNavigationTest io.lumatv.launcher.test/androidx.test.runner.AndroidJUnitRunner
    $output | Tee-Object -FilePath artifacts\device-tests.log
    if (($output -join "`n") -notmatch 'OK \(13 tests\)') { throw 'Remote-control acceptance failed; inspect artifacts\device-tests.log.' }
} finally {
    foreach ($name in $installedFixtures) {
        $present = & $adb -s $Serial shell pm path "io.lumatv.fixture.$name"
        if ($present -match '^package:') { & $adb -s $Serial uninstall "io.lumatv.fixture.$name" }
    }
    $env:GRADLE_USER_HOME = $previousGradle
    $env:ANDROID_HOME = $previousSdk
    Pop-Location
}
