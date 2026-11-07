param([string]$BuildName = 'build-corrective-tests',
    [ValidateSet(256,384,512,1024)][int]$CompilerHeapMiB = 1024,
    [string]$ToolchainRoot = $env:WATCHMAN_TOOLCHAIN_ROOT)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$projectRoot = (Resolve-Path (Join-Path $sourceRoot '..')).Path
if ($BuildName -notmatch '^build-[a-zA-Z0-9-]+$') { throw 'Use a simple build directory name' }
$build = Join-Path $sourceRoot $BuildName
New-Item -ItemType Directory -Force -Path $build | Out-Null
if ([string]::IsNullOrWhiteSpace($ToolchainRoot)) { $ToolchainRoot = Join-Path $projectRoot 'toolchain' }
$java = Join-Path $ToolchainRoot 'jdk/jdk-17.0.20.1+1/bin/java.exe'
$kotlin = Join-Path $ToolchainRoot 'kotlin/kotlinc/lib'
$sources = @(
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/NoCamsObstaclePlanner.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/NoCamsRouteMonitor.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RouteCorridor.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RoutePlanningPolicy.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/NoCamsObstaclePlannerSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RouteProximityIndexSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/GeocoderEvidenceSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/SourceMappedRoadModel.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/SourceMappedRoadModelSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/FullRoadPlanningBudget.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/FullRoadPlanningBudgetSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/storage/DeviceSearchRegion.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/DeviceSearchRegionSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/SourceRoadTiming.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/SourceRoadTimingSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RoadPolicyTimeline.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RoadPolicyTimelineSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/GraphRouteMaterializer.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/GraphRouteMaterializerSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RoadSpeedEstimate.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RoadSpeedEstimateSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/data/Models.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/util/Geo.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RouteAnalyzer.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RouteProximityIndex.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/storage/DeviceIdentity.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/storage/RoutingDeviceSnapshot.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RoadDeviceExposureIndex.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RoadDeviceExposureSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/NoCamsGraphSearch.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/NoCamsGraphSearchSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/EndpointAccessPolicy.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/EndpointAccessPolicySmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RoadEndpointAnchor.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RoadArcPortion.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/PartialRoadTraversalSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/PackedSpatialTree.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/PackedSpatialTreeSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RoadSnapGeometry.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RoadSnapGeometrySmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/PackedNodeReferences.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/PackedNodeReferencesSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/MotorcarGraphTraversal.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/MotorcarGraphTraversalSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/TurnRestrictionPolicy.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/TurnRestrictionPolicySmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/MotorcarRoadProfile.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/MotorcarRoadProfileSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/VehicleLimitPolicy.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/VehicleLimitPolicySmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/TurnRelationCompiler.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/TurnRelationCompilerSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/TurnRestrictionMachine.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/TurnRestrictionMachineSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/MotorcarTagPolicy.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/MotorcarTagPolicySmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/RestrictionCondition.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/RestrictionConditionSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/routing/ConditionalRestriction.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/ConditionalRestrictionSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/util/SharedAssetGate.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/SharedAssetGateSmoke.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/alerts/SpeechInitializationGate.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/alerts/SpeechIo.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/util/CoalescingWorker.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/export/MapLabelLayout.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/ui/LaunchPresentation.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/alerts/SpeechDeliveryQueue.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/alerts/BritishVoicePolicy.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/alerts/ProximityEncounterTracker.kt'),
    (Join-Path $sourceRoot 'app/src/main/java/com/bggg/watchman/alerts/ProximityAudioPolicy.kt'),
    (Join-Path $sourceRoot 'app/src/test/java/com/bggg/watchman/CorrectiveBehaviorSmoke.kt')
)
$jar = Join-Path $build 'corrective.jar'
& $java "-Xmx${CompilerHeapMiB}m" '-XX:MaxMetaspaceSize=192m' '-XX:ActiveProcessorCount=2' '-cp' "$kotlin/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @sources '-classpath' "$kotlin/kotlin-stdlib.jar" '-include-runtime' '-jvm-target' '1.8' '-d' $jar
if ($LASTEXITCODE -ne 0) { throw 'Corrective test compilation failed' }
& $java '-Xmx128m' '-XX:ActiveProcessorCount=2' '-jar' $jar
if ($LASTEXITCODE -ne 0) { throw 'Corrective behavior tests failed' }
