param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$oldGradle = $env:GRADLE_USER_HOME
$oldSdk = $env:ANDROID_HOME
$oldOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:GRADLE_USER_HOME = Join-Path $root '.gradle-user'
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if ($env:HTTPS_PROXY) {
        $proxyUri = [Uri]$env:HTTPS_PROXY
        if (-not $proxyUri.UserInfo) {
            $env:JAVA_TOOL_OPTIONS = "$oldOptions -Dhttps.proxyHost=$($proxyUri.Host) -Dhttps.proxyPort=$($proxyUri.Port) -Dhttp.proxyHost=$($proxyUri.Host) -Dhttp.proxyPort=$($proxyUri.Port) -Dhttp.nonProxyHosts=localhost|127.*"
        }
    }
    Push-Location $root
    try {
        $arguments = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', '--console=plain')
        if ($Offline) { $arguments += '--offline' }
        & .\gradlew.bat @arguments
        if ($LASTEXITCODE -ne 0) { throw "Build failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
} finally {
    $env:GRADLE_USER_HOME = $oldGradle
    $env:ANDROID_HOME = $oldSdk
    $env:JAVA_TOOL_OPTIONS = $oldOptions
}
