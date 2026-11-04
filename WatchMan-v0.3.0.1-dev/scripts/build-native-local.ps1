param(
    [string]$BuildDir = ''
)

$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$projectRoot = (Resolve-Path (Join-Path $sourceRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($BuildDir)) {
    $build = Join-Path $sourceRoot 'build-local'
} else {
    $build = [IO.Path]::GetFullPath($BuildDir)
}
$sourcePrefix = $sourceRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $build.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing build directory outside the source tree: $build"
}

$jdk = Join-Path $projectRoot 'toolchain\jdk\jdk-17.0.20.1+1'
$sdk = Join-Path $projectRoot 'toolchain\android-sdk'
$kotlinHome = Join-Path $projectRoot 'toolchain\kotlin\kotlinc'
$java = Join-Path $jdk 'bin\java.exe'
$javac = Join-Path $jdk 'bin\javac.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
$androidJar = Join-Path $sdk 'platforms\android-36\android.jar'
$aapt2 = Join-Path $sdk 'build-tools\36.0.0\aapt2.exe'
$d8Jar = Join-Path $sdk 'build-tools\36.0.0\lib\d8.jar'
$zipalign = Join-Path $sdk 'build-tools\36.0.0\zipalign.exe'
$stdlib = Join-Path $kotlinHome 'lib\kotlin-stdlib.jar'
$stdlib7 = Join-Path $kotlinHome 'lib\kotlin-stdlib-jdk7.jar'
$stdlib8 = Join-Path $kotlinHome 'lib\kotlin-stdlib-jdk8.jar'
$kotlinCompilerClasspath = Join-Path $kotlinHome 'lib\*'
$requiredTools = @($java, $javac, $jar, $androidJar, $aapt2, $d8Jar, $zipalign, $stdlib, $stdlib7, $stdlib8)
foreach ($tool in $requiredTools) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing required tool: $tool" }
}

if (Test-Path -LiteralPath $build) { Remove-Item -LiteralPath $build -Recurse -Force }
$compiled = Join-Path $build 'compiled-res.zip'
$generated = Join-Path $build 'gen'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$dexVerify = Join-Path $build 'dex-verify'
New-Item -ItemType Directory -Force -Path $build, $generated, $classes, $dex, $dexVerify | Out-Null

$manifest = Join-Path $sourceRoot 'app\src\main\AndroidManifest.xml'
$resources = Join-Path $sourceRoot 'app\src\main\res'
$assets = Join-Path $sourceRoot 'app\src\main\assets'
$baseApk = Join-Path $build 'base.apk'
$unaligned = Join-Path $build 'unaligned.apk'
$outApk = Join-Path $build 'WatchMan-v0.3.0-unsigned.apk'

Write-Output 'GATE 1/8: AAPT2 resource compile'
& $aapt2 compile --dir $resources -o $compiled
if ($LASTEXITCODE -ne 0) { throw "AAPT2 compile failed: $LASTEXITCODE" }

Write-Output 'GATE 2/8: AAPT2 resource link and R generation'
$linkArgs = @(
    'link', '-o', $baseApk, '-I', $androidJar, '--manifest', $manifest, '--java', $generated,
    '--min-sdk-version', '26', '--target-sdk-version', '36', '--version-code', '30',
    '--version-name', '0.3.0', $compiled
)
& $aapt2 @linkArgs
if ($LASTEXITCODE -ne 0) { throw "AAPT2 link failed: $LASTEXITCODE" }

Write-Output 'GATE 3/8: generated R javac and full Kotlin compile'
$rJava = @(Get-ChildItem -LiteralPath $generated -Filter '*.java' -Recurse -File | ForEach-Object FullName)
if ($rJava.Count -gt 0) {
    $javacArgs = @('-source', '8', '-target', '8', '-bootclasspath', $androidJar, '-d', $classes) + $rJava
    & $javac @javacArgs
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
}
$kotlinFiles = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'app\src\main\java') -Filter '*.kt' -Recurse -File | ForEach-Object FullName)
if ($kotlinFiles.Count -eq 0) { throw 'No Kotlin application sources found' }
$compileClasspath = @($androidJar, $classes, $stdlib, $stdlib7, $stdlib8) -join ';'
$kotlinArgs = @('-Xmx1536m', '-cp', $kotlinCompilerClasspath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler') + $kotlinFiles + @('-classpath', $compileClasspath, '-jvm-target', '1.8', '-d', $classes)
& $java @kotlinArgs
if ($LASTEXITCODE -ne 0) { throw "Kotlin compile failed: $LASTEXITCODE" }

Write-Output 'GATE 4/8: application JAR and official D8'
$appJar = Join-Path $build 'app-classes.jar'
& $jar cf $appJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed: $LASTEXITCODE" }
$d8Args = @('-cp', $d8Jar, 'com.android.tools.r8.D8', '--lib', $androidJar, '--min-api', '26', '--output', $dex, $appJar, $stdlib, $stdlib7, $stdlib8)
& $java @d8Args
if ($LASTEXITCODE -ne 0) { throw "D8 failed: $LASTEXITCODE" }
$dexFiles = @(Get-ChildItem -LiteralPath $dex -Filter 'classes*.dex' -File | Sort-Object Name)
if ($dexFiles.Count -eq 0) { throw 'D8 produced no classes*.dex' }

Write-Output 'GATE 5/8: official D8 structural re-feed'
$refeedArgs = @('-cp', $d8Jar, 'com.android.tools.r8.D8', '--lib', $androidJar, '--min-api', '26', '--output', $dexVerify) + @($dexFiles.FullName)
& $java @refeedArgs
if ($LASTEXITCODE -ne 0) { throw "D8 structural re-feed failed: $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath (Join-Path $dexVerify 'classes.dex'))) { throw 'D8 re-feed produced no classes.dex' }

Write-Output 'GATE 6/8: APK assembly'
Copy-Item -LiteralPath $baseApk -Destination $unaligned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unaligned, [IO.Compression.ZipArchiveMode]::Update)
try {
    $assetFiles = @(Get-ChildItem -LiteralPath $assets -Recurse -File)
    if ($assetFiles.Count -eq 0) { throw 'No Android assets found' }
    foreach ($assetFile in $assetFiles) {
        $relative = [IO.Path]::GetRelativePath($assets, $assetFile.FullName).Replace('\', '/')
        $entryName = "assets/$relative"
        $existingAsset = $archive.GetEntry($entryName)
        if ($null -ne $existingAsset) { $existingAsset.Delete() }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $assetFile.FullName, $entryName, [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
        $existing = $archive.GetEntry($dexFile.Name)
    }
    foreach ($dexFile in $dexFiles) {
        if ($null -ne $existing) { $existing.Delete() }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dexFile.FullName, $dexFile.Name, [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
    }
} finally {
    $archive.Dispose()
}

Write-Output 'GATE 7/8: 16 KiB zipalign write and check'
& $zipalign -f -P 16 4 $unaligned $outApk
if ($LASTEXITCODE -ne 0) { throw "zipalign write failed: $LASTEXITCODE" }
& $zipalign -c -P 16 4 $outApk
if ($LASTEXITCODE -ne 0) { throw "zipalign check failed: $LASTEXITCODE" }

Write-Output 'GATE 8/8: ZIP, required entries, unsigned state, badging, and launcher'
$zip = [IO.Compression.ZipFile]::OpenRead($outApk)
try {
    $names = @($zip.Entries | ForEach-Object FullName)
    foreach ($required in @('AndroidManifest.xml', 'resources.arsc', 'classes.dex')) {
        if ($names -notcontains $required) { throw "APK missing required root entry: $required" }
    }
    $signatureEntries = @($names | Where-Object { $_ -match '^META-INF/.+\.(RSA|DSA|EC|SF)$' })
    if ($signatureEntries.Count -ne 0) { throw 'Unexpected APK signature payload' }
    foreach ($entry in $zip.Entries) {
        $stream = $entry.Open()
        try {
            $buffer = New-Object byte[] 65536
            while ($stream.Read($buffer, 0, $buffer.Length) -gt 0) { }
        } finally {
            $stream.Dispose()
        }
    }
} finally {
    $zip.Dispose()
}
$badging = & $aapt2 dump badging $outApk
if ($LASTEXITCODE -ne 0) { throw "AAPT2 badging failed: $LASTEXITCODE" }
$badging | Set-Content -LiteralPath (Join-Path $build 'badging.txt') -Encoding utf8
if (($badging -join "`n") -notmatch "package: name='com\.bggg\.watchman' versionCode='30' versionName='0\.3\.0'") { throw 'APK package/version metadata mismatch' }
if (($badging -join "`n") -notmatch "launchable-activity: name='com\.bggg\.watchman\.MainActivity'") { throw 'MainActivity is not launchable' }

$hash = (Get-FileHash -LiteralPath $outApk -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $outApk).Length
Write-Output "NATIVE_BUILD_PASS apk=$outApk bytes=$size sha256=$hash dex_count=$($dexFiles.Count)"
