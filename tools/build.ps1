param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Task = ':app:assembleAppDebug',
    [string]$Proxy = '',
    [string[]]$ExtraArgs = @()
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
if (!$JavaHome) {
    $JavaHome = Join-Path (Split-Path -Parent $root) 'OmiComic\.cache\android-tools\local-jdk17-20261027\jdk-17.0.20.1+1'
}
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Set JavaHome to a JDK 17 installation.' }
$env:JAVA_HOME = $JavaHome
$env:GRADLE_USER_HOME = Join-Path $root '.gradle-user-home'
if ($Proxy) {
    $proxyUri = [uri]$Proxy
    $env:GRADLE_OPTS = '-Dhttps.proxyHost=' + $proxyUri.Host + ' -Dhttps.proxyPort=' + $proxyUri.Port + ' -Dhttp.proxyHost=' + $proxyUri.Host + ' -Dhttp.proxyPort=' + $proxyUri.Port
}
if ($Task -match 'Release') {
    $signingDir = Join-Path $root '.android-signing'
    $keyFile = Join-Path $signingDir 'omireader-release.jks'
    $passwordFile = Join-Path $signingDir 'password.txt'
    New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
    if (!(Test-Path -LiteralPath $keyFile)) {
        if (Test-Path -LiteralPath $passwordFile) { throw 'Restore the original signing key before rebuilding.' }
        $signingPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
        [IO.File]::WriteAllText($passwordFile,$signingPassword)
        $env:OMIREADER_SIGNING_PASSWORD = $signingPassword
        & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $keyFile -storetype JKS -alias omireader -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=OmiReader, O=Lorcin' -storepass:env OMIREADER_SIGNING_PASSWORD -keypass:env OMIREADER_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Unable to create OmiReader signing key.' }
        Remove-Item Env:OMIREADER_SIGNING_PASSWORD
    }
    $env:ORG_GRADLE_PROJECT_RELEASE_STORE_FILE = $keyFile
    $env:ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD = [IO.File]::ReadAllText($passwordFile)
    $env:ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS = 'omireader'
    $env:ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD = $env:ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD
}
& .\gradlew.bat $Task --console=plain --max-workers=4 @ExtraArgs
$buildExitCode = $LASTEXITCODE
foreach ($name in @('ORG_GRADLE_PROJECT_RELEASE_STORE_FILE','ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD','ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS','ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD')) {
    Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue
}
if ($buildExitCode -ne 0) { throw "Android build failed ($buildExitCode)." }
