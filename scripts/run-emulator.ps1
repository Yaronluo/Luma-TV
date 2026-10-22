param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not $Serial.StartsWith('emulator-')) { throw 'This script only operates on the existing emulator.' }
$avd = & $adb -s $Serial emu avd name
if ($LASTEXITCODE -ne 0 -or ($avd -join "`n") -notmatch '(?m)^BILI_TV_API_34\s*$') { throw 'Target is not BILI_TV_API_34.' }
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Run scripts\build.ps1 first.' }
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Installation failed.' }
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $Serial shell am start -W -n io.lumatv.launcher/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Launch failed.' }
