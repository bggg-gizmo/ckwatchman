param(
    [string]$BuildDir = '',
    [ValidateSet(256,384,512,1024,1536)][int]$CompilerHeapMiB = 1536,
    [string]$ToolchainRoot = $env:WATCHMAN_TOOLCHAIN_ROOT
)

$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$projectRoot = (Resolve-Path (Join-Path $sourceRoot '..')).Path
$build = if ([string]::IsNullOrWhiteSpace($BuildDir)) {
    Join-Path $sourceRoot 'build-core-test'
} else {
    [IO.Path]::GetFullPath($BuildDir)
}
$sourcePrefix = $sourceRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $build.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing build directory outside the source tree: $build"
}

if ([string]::IsNullOrWhiteSpace($ToolchainRoot)) { $ToolchainRoot = Join-Path $projectRoot 'toolchain' }
$jdk = Join-Path $ToolchainRoot 'jdk\jdk-17.0.20.1+1'
$kotlinHome = Join-Path $ToolchainRoot 'kotlin\kotlinc'
$java = Join-Path $jdk 'bin\java.exe'
$compilerClasspath = Join-Path $kotlinHome 'lib\*'
$stdlib = Join-Path $kotlinHome 'lib\kotlin-stdlib.jar'
$stdlib7 = Join-Path $kotlinHome 'lib\kotlin-stdlib-jdk7.jar'
$stdlib8 = Join-Path $kotlinHome 'lib\kotlin-stdlib-jdk8.jar'
foreach ($required in @($java, $stdlib, $stdlib7, $stdlib8)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing test runtime: $required" }
}

if (Test-Path -LiteralPath $build) { Remove-Item -LiteralPath $build -Recurse -Force }
New-Item -ItemType Directory -Path $build | Out-Null
$shellTest = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'test-core.sh'))
$match = [regex]::Match($shellTest, '(?s)cat > "\$OUT/CoreSmoke\.kt" <<''KOT''\r?\n(.*?)\r?\nKOT')
if (-not $match.Success) { throw 'Unable to extract CoreSmoke.kt from test-core.sh' }
$smoke = Join-Path $build 'CoreSmoke.kt'
[IO.File]::WriteAllText($smoke, $match.Groups[1].Value, [Text.UTF8Encoding]::new($false))

$relativeSources = @(
    'app\src\main\java\com\bggg\watchman\data\Models.kt',
    'app\src\main\java\com\bggg\watchman\alerts\AlertSpeech.kt',
    'app\src\main\java\com\bggg\watchman\alerts\ProximityAudioPolicy.kt',
    'app\src\main\java\com\bggg\watchman\alerts\ProximityToneWaveform.kt',
    'app\src\main\java\com\bggg\watchman\alerts\ProximityEncounterTracker.kt',
    'app\src\main\java\com\bggg\watchman\alerts\ProximityEncounterSession.kt',
    'app\src\main\java\com\bggg\watchman\alerts\ProximityAudioOwnership.kt',
    'app\src\main\java\com\bggg\watchman\cameras\CameraClassifier.kt',
    'app\src\main\java\com\bggg\watchman\routing\RouteScorer.kt',
    'app\src\main\java\com\bggg\watchman\routing\RoutePlanningPolicy.kt',
    'app\src\main\java\com\bggg\watchman\routing\RouteContextPolicy.kt',
    'app\src\main\java\com\bggg\watchman\routing\PendingRouteAction.kt',
    'app\src\main\java\com\bggg\watchman\export\ExportWorkflowContext.kt',
    'app\src\main\java\com\bggg\watchman\routing\RouteAnalyzer.kt',
    'app\src\main\java\com\bggg\watchman\routing\RouteGeometry.kt',
    'app\src\main\java\com\bggg\watchman\routing\RouteShaper.kt',
    'app\src\main\java\com\bggg\watchman\routing\RouteProximityIndex.kt',
    'app\src\main\java\com\bggg\watchman\cameras\AcquisitionPriorityCoordinator.kt',
    'app\src\main\java\com\bggg\watchman\cameras\ProgressiveCoverageOrder.kt',
    'app\src\main\java\com\bggg\watchman\routing\RouteCorridor.kt',
    'app\src\main\java\com\bggg\watchman\geo\StateBoundaryGeometry.kt',
    'app\src\main\java\com\bggg\watchman\routing\NavigationProgress.kt',
    'app\src\main\java\com\bggg\watchman\net\OperationDeadline.kt',
    'app\src\main\java\com\bggg\watchman\service\NavigationRuntimePolicy.kt',
    'app\src\main\java\com\bggg\watchman\service\NavigationAnalyticsPolicy.kt',
    'app\src\main\java\com\bggg\watchman\ui\ManeuverVisual.kt',
    'app\src\main\java\com\bggg\watchman\ui\RouteViewport.kt',
    'app\src\main\java\com\bggg\watchman\ui\MapDeviceDeclutter.kt',
    'app\src\main\java\com\bggg\watchman\ui\MapMemoryPolicy.kt',
    'app\src\main\java\com\bggg\watchman\util\Geo.kt',
    'app\src\main\java\com\bggg\watchman\util\ExecutorShutdown.kt',
    'app\src\main\java\com\bggg\watchman\navigation\NavigationActivationCoordinator.kt',
    'app\src\main\java\com\bggg\watchman\storage\RouteIdentity.kt'
)
$sources = @($relativeSources | ForEach-Object { Join-Path $sourceRoot $_ }) + @($smoke)
$jar = Join-Path $build 'core-smoke.jar'
$compileClasspath = @($stdlib, $stdlib7, $stdlib8) -join ';'
$compilerArgs = @(
    "-Xmx${CompilerHeapMiB}m", '-XX:MaxMetaspaceSize=192m', '-XX:ActiveProcessorCount=2', '-cp', $compilerClasspath,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler'
) + $sources + @('-classpath', $compileClasspath, '-include-runtime', '-jvm-target', '1.8', '-d', $jar)
& $java @compilerArgs
if ($LASTEXITCODE -ne 0) { throw "Core Kotlin compilation failed: $LASTEXITCODE" }
& $java '-Xmx128m' '-XX:ActiveProcessorCount=2' -jar $jar
if ($LASTEXITCODE -ne 0) { throw "Core smoke execution failed: $LASTEXITCODE" }
