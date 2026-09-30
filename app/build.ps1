param([switch]$KeepBuildDirectory)
$ErrorActionPreference = 'Stop'

# Pure command-line build for the DiDi ad-clean LSPosed module (API 102).
# javac -> d8 -> aapt2 -> zipalign -> apksigner. No native libs, no third-party jars.

$app = $PSScriptRoot
# Toolchain autodetection. ASCII only on purpose: Windows PowerShell 5.1 decodes a
# .ps1 without a UTF-8 BOM as ANSI/GBK, and stray non-ASCII bytes can break parsing.
# Override with $env:DAC_SDK / $env:DAC_JDK.
$workspace = Split-Path $app -Parent | Split-Path -Parent
$sdkCandidates = @(@($env:DAC_SDK, $env:ANDROID_SDK, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
                   (Join-Path $workspace '.android_build_tools\android-sdk'),
                   'C:\Android\Sdk', (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
               Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms')) } | Select-Object -Unique)
if ($sdkCandidates.Count -eq 0) { throw 'Android SDK not found. Set $env:DAC_SDK.' }
$sdk = $sdkCandidates[0]
# Newest platform / build-tools that is actually usable, so the build is not pinned
# to one SDK image. 'android-34-2'-style side installs are ignored on purpose.
$platforms = @(Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Directory |
    Where-Object { $_.Name -match '^android-\d+$' -and (Test-Path (Join-Path $_.FullName 'android.jar')) } |
    Sort-Object { [int]($_.Name -replace '\D', '') } -Descending)
if ($platforms.Count -eq 0) { throw "No usable platform (android.jar) under $sdk\platforms" }
$platform = $platforms[0].Name
$buildTools = @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { (Test-Path (Join-Path $_.FullName 'aapt2.exe')) -and (Test-Path (Join-Path $_.FullName 'apksigner.bat')) } |
    Sort-Object { [version]($_.Name) } -Descending)
if ($buildTools.Count -eq 0) { throw "No usable build-tools under $sdk\build-tools" }
$tools = $buildTools[0].FullName
$androidJarSource = Join-Path $sdk "platforms\$platform\android.jar"
$jdkCandidates = @(@($env:DAC_JDK, $env:JAVA_HOME, (Join-Path $workspace '.android_build_tools\jdk17'),
                   'C:\Program Files\AdoptOpenJDK\jdk-17.0.0.20-hotspot') |
                 Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe')) } | Select-Object -Unique)
if ($jdkCandidates.Count -eq 0) { throw 'JDK 17 not found. Set $env:DAC_JDK.' }
$jdk = $jdkCandidates[0]
Write-Host "sdk         : $sdk (platform $platform)"
Write-Host "build-tools : $tools"
Write-Host "jdk         : $jdk"
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
$keytool = Join-Path $jdk 'bin\keytool.exe'
$aapt2 = Join-Path $tools 'aapt2.exe'
# Some build-tools installs ship a d8.bat whose d8.jar is missing (34.0.0 here has
# lib\apksigner.jar only), and d8.bat then runs a classpath that does not exist and
# dies with a bare ClassNotFoundException. Resolve the D8 jar explicitly instead.
# Order matters: an explicit R8_JAR wins, then a standalone modern r8-<ver>.jar under
# <sdk>\d8, and only then whatever d8.jar the SDK ships. The d8.jar bundled with old
# build-tools (R8 3.3.20) dies here with "Cannot invoke String.length() because
# <parameter1> is null" while dexing, so it must be the last resort, not the first.
$d8Candidates = @()
if ($env:R8_JAR) { $d8Candidates += $env:R8_JAR }
$d8Candidates += @(Get-ChildItem -LiteralPath (Join-Path $sdk 'd8') -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'r8-*.jar' } | Sort-Object Name -Descending | ForEach-Object FullName)
$d8Candidates += @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'd8.jar' } | ForEach-Object FullName)
$d8Jar = $d8Candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $d8Jar) { throw "No D8 jar found. Set `$env:R8_JAR to a com.android.tools:r8 jar from dl.google.com/dl/android/maven2." }
Write-Host "d8          : $d8Jar"
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'

$version = '0.6.0'
$stage = Join-Path $env:TEMP ('didiadclean-' + [guid]::NewGuid().ToString('N'))
$dist = Join-Path $app 'dist'
$keystore = Join-Path $app 'debug.keystore'
$alias = 'qqmusicclean'
$output = Join-Path $dist ("didi-adclean-v{0}.apk" -f $version)
$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path

foreach ($needed in @($androidJarSource, $javac, $java, $jar, $keytool, $aapt2, $d8Jar, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $needed)) { throw "Build tool missing: $needed" }
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -LiteralPath $androidJarSource -Destination $stage
$androidJar = Join-Path $stage 'android.jar'
foreach ($folder in @('stub-src', 'src', 'res', 'META-INF', 'libs')) {
    $path = Join-Path $app $folder
    if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination $stage -Recurse -Force }
}
Copy-Item -LiteralPath (Join-Path $app 'AndroidManifest.xml') -Destination $stage
foreach ($folder in @('stubs', 'classes', 'dex', 'out')) {
    New-Item -ItemType Directory -Path (Join-Path $stage $folder) -Force | Out-Null
}

function Run-Native([string]$name, [scriptblock]$action) {
    & $action
    if ($LASTEXITCODE -ne 0) { throw "$name failed with exit code $LASTEXITCODE" }
}

function Get-FilesByExtension([string]$dir, [string]$ext, [switch]$Recurse) {
    # Deliberately not using Get-ChildItem -Filter here: the workspace path contains
    # non-ASCII characters, and PS 5.1 was observed returning 0 matches intermittently
    # for the same directory, which would silently skip every source file.
    if (-not (Test-Path -LiteralPath $dir)) { return @() }
    $items = if ($Recurse) { Get-ChildItem -LiteralPath $dir -Recurse -File } else { Get-ChildItem -LiteralPath $dir -File }
    return @($items | Where-Object { $_.Extension -eq $ext } | ForEach-Object FullName)
}

try {
    $stubs = Get-FilesByExtension (Join-Path $stage 'stub-src') '.java' -Recurse
    $sources = Get-FilesByExtension (Join-Path $stage 'src') '.java' -Recurse
    Write-Host 'Compiling API stubs and module'
    Run-Native 'API stub compilation' { & $javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $androidJar -d (Join-Path $stage 'stubs') @stubs }
    $serviceJar = Join-Path $stage 'libs\service-classes.jar'
    $compilePath = (Join-Path $stage 'stubs')
    if (Test-Path -LiteralPath $serviceJar) { $compilePath = "$compilePath;$serviceJar" }
    Run-Native 'Module compilation' { & $javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $androidJar -classpath $compilePath -d (Join-Path $stage 'classes') @sources }
    $classesJar = Join-Path $stage 'classes.jar'
    Run-Native 'JAR creation' { & $jar -cf $classesJar -C (Join-Path $stage 'classes') . }
    $dexInputs = @($classesJar)
    if (Test-Path -LiteralPath $serviceJar) { $dexInputs += $serviceJar }
    Run-Native 'DEX conversion' { & $java -Xmx3072M -cp $d8Jar com.android.tools.r8.D8 --min-api 26 --lib $androidJar --output (Join-Path $stage 'dex') @dexInputs }

    Write-Host 'Packaging Android resources'
    $compiledRes = Join-Path $stage 'resources.zip'
    Run-Native 'Resource compilation' { & $aapt2 compile --dir (Join-Path $stage 'res') -o $compiledRes }
    $unsigned = Join-Path $stage 'out\module.apk'
    Run-Native 'APK linking' { & $aapt2 link -o $unsigned --manifest (Join-Path $stage 'AndroidManifest.xml') -I $androidJar --min-sdk-version 26 --target-sdk-version 34 $compiledRes }
    Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $archive = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        Get-ChildItem -LiteralPath (Join-Path $stage 'dex') -Filter 'classes*.dex' | ForEach-Object {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $_.Name) | Out-Null
        }
        Get-ChildItem -LiteralPath (Join-Path $stage 'META-INF') -Recurse -File | ForEach-Object {
            $relative = $_.FullName.Substring($stage.Length + 1).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $relative) | Out-Null
        }
    } finally { $archive.Dispose() }
    $aligned = Join-Path $stage 'out\module-aligned.apk'
    Run-Native 'APK alignment' { & $zipalign -f 4 $unsigned $aligned }

    $env:DIDICLEAN_KEYPASS = 'android'
    if (-not (Test-Path -LiteralPath $keystore)) {
        Run-Native 'Test key generation' { & $keytool -genkeypair -keystore $keystore -storepass:env DIDICLEAN_KEYPASS -keypass:env DIDICLEAN_KEYPASS -alias $alias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=DiDiAdClean, O=Local, C=CN' }
    }
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    Run-Native 'APK signing' { & $apksigner sign --ks $keystore --ks-key-alias $alias --ks-pass env:DIDICLEAN_KEYPASS --key-pass env:DIDICLEAN_KEYPASS --out $output $aligned }
    Run-Native 'Signature verification' { & $apksigner verify $output }
    Write-Host "Built $output"
    (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
    Write-Host '--- APK entries ---'
    $z = [System.IO.Compression.ZipFile]::OpenRead($output)
    $z.Entries | Select-Object FullName, Length | Format-Table -AutoSize
    $z.Dispose()
} finally {
    Remove-Item Env:DIDICLEAN_KEYPASS -ErrorAction SilentlyContinue
    if (-not $KeepBuildDirectory -and (Test-Path -LiteralPath $stage)) {
        $resolved = [IO.Path]::GetFullPath($stage)
        $tempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
        if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolved).StartsWith('didiadclean-')) {
            throw "Refusing to remove unexpected build directory: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
