param([switch]$KeepBuildDirectory)
$ErrorActionPreference = 'Stop'

# Pure command-line build for the DiDi ad-clean LSPosed module (API 102).
# javac -> d8 -> aapt2 -> zipalign -> apksigner. No native libs, no third-party jars.

$app = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:\AndroidSdk' }
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Users\29092\AppData\Local\Programs\jdk17' }
$tools = Join-Path $sdk 'build-tools\35.0.0'
$androidJarSource = Join-Path $sdk 'platforms\android-35\android.jar'
$javac = Join-Path $jdk 'bin\javac.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
$keytool = Join-Path $jdk 'bin\keytool.exe'
$aapt2 = Join-Path $tools 'aapt2.exe'
$d8 = Join-Path $tools 'd8.bat'
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

foreach ($needed in @($androidJarSource, $javac, $jar, $aapt2, $d8, $zipalign, $apksigner)) {
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

try {
    $stubs = @(Get-ChildItem -LiteralPath (Join-Path $stage 'stub-src') -Recurse -Filter '*.java' | ForEach-Object FullName)
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $stage 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
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
    Run-Native 'DEX conversion' { & $d8 --min-api 26 --lib $androidJar --output (Join-Path $stage 'dex') @dexInputs }

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
