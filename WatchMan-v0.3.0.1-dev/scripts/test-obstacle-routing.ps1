param([string]$ToolchainRoot = $env:WATCHMAN_TOOLCHAIN_ROOT,
    [string]$JsonJar = '', [ValidateSet(256,384,512)][int]$CompilerHeapMiB = 256)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($ToolchainRoot)) { $ToolchainRoot = Join-Path (Split-Path $sourceRoot) 'toolchain' }
if ([string]::IsNullOrWhiteSpace($JsonJar)) { $JsonJar = Join-Path $ToolchainRoot 'host-test/json-20260814.jar' }
if ((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne '7DD2DE1533E766E2B6C07D8194DA85145AD313A167A4760050F5C9B6839604FE') {
    throw 'Supply the unmodified org.json:json:20260814 host runtime (see upstream JSON-java). No automatic download performed.'
}
$java = Join-Path $ToolchainRoot 'jdk/jdk-17.0.20.1+1/bin/java.exe'
$kotlin = Join-Path $ToolchainRoot 'kotlin/kotlinc/lib'
$build = Join-Path $sourceRoot 'build-obstacle-tests'
New-Item -ItemType Directory -Force -Path $build | Out-Null
$base = Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman'
$sources = @('data/Models.kt','data/ProviderConfig.kt','util/Geo.kt',
    'routing/RoutePlanningPolicy.kt','routing/NoCamsObstaclePlanner.kt','routing/ValhallaObstacleRoutingProvider.kt','routing/CameraExclusionUnion.kt','routing/RoadExclusionEncoding.kt',
    'routing/RoadAwareExposure.kt','routing/RoadExposureObservationJson.kt',
    'cameras/CameraClassifier.kt',
    'routing/RoadCoverageSource.kt','routing/RoadSourceDeviceNormalizer.kt','routing/NoCamsRouteMonitor.kt',
    'routing/OsrmRoutingProvider.kt','routing/RouteShaper.kt','routing/RouteGeometry.kt','routing/RouteProximityIndex.kt','routing/RouteAnalyzer.kt',
    'storage/DeviceIdentity.kt','storage/DeviceRecordMerge.kt','storage/RoutingDeviceSnapshot.kt','storage/ActiveRouteStateCodec.kt',
    'storage/RouteIdentity.kt','storage/SearchResultEvidenceJson.kt','net/HttpClient.kt','net/OperationDeadline.kt',
    'service/NavigationRuntimePolicy.kt') | ForEach-Object { Join-Path $base $_ }
$sources += Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/ObstacleRoutingAdapterSmoke.kt'
$sources += Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/CameraExclusionUnionSmoke.kt'
$sources += Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RoadAwarenessJsonSmoke.kt'
$sources += Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RoadAwareExposureSmoke.kt'
$sources += Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RoadCoverageSourceSmoke.kt'
$sources += Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/DeviceRecordMergeSmoke.kt'
$jar = Join-Path $build 'obstacle-tests.jar'
$classpath = "$kotlin/kotlin-stdlib.jar;$JsonJar"
& $java "-Xmx${CompilerHeapMiB}m" '-XX:MaxMetaspaceSize=192m' '-XX:ActiveProcessorCount=2' '-cp' "$kotlin/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @sources '-classpath' $classpath '-include-runtime' '-jvm-target' '1.8' '-d' $jar
if ($LASTEXITCODE -ne 0) { throw 'Obstacle adapter test compilation failed' }
& $java '-Xmx128m' '-XX:ActiveProcessorCount=2' "-Dwatchman.test.cache.root=$build/cache-fixtures" '-cp' "$jar;$JsonJar" com.bggg.watchman.ObstacleRoutingAdapterSmokeKt
if ($LASTEXITCODE -ne 0) { throw 'Obstacle adapter tests failed' }
