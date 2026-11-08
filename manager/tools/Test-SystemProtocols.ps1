param(
    [string] $GradleCache = $env:GRADLE_USER_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $GradleCache) { $GradleCache = Join-Path $env:USERPROFILE '.gradle' }
$taskModules = Join-Path $GradleCache 'caches/modules-2/files-2.1'
function Find-CachedJar([string] $Group, [string] $Artifact, [string] $Version) {
    $taskDirectory = Join-Path $taskModules "$Group/$Artifact/$Version"
    $taskJar = Get-ChildItem -LiteralPath $taskDirectory -Recurse -Filter "$Artifact-$Version.jar" | Select-Object -First 1
    if (-not $taskJar) { throw "Missing cached dependency: $Group`:$Artifact`:$Version" }
    return $taskJar.FullName
}
$taskCompiler = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.0.21'
$taskStdlib = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$taskScript = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.0.21'
$taskReflect = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'
$taskTrove = Find-CachedJar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'
$taskAnnotations = Find-CachedJar 'org.jetbrains' 'annotations' '23.0.0'
$taskPlugin = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-serialization-compiler-plugin-embeddable' '2.0.21'
$taskJson = Find-CachedJar 'org.jetbrains.kotlinx' 'kotlinx-serialization-json-jvm' '1.7.3'
$taskSerialization = Find-CachedJar 'org.jetbrains.kotlinx' 'kotlinx-serialization-core-jvm' '1.7.3'
$taskCoroutines = Find-CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.9.0'
$taskJunit = Find-CachedJar 'junit' 'junit' '4.13.2'
$taskHamcrest = Find-CachedJar 'org.hamcrest' 'hamcrest-core' '1.3'
$taskManager = Split-Path -Parent $PSScriptRoot
$taskOutput = Join-Path $taskManager 'app/build/system-protocol-tests'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskMain = Join-Path $taskManager 'app/src/main/java/io/milksu/manager'
$taskSources = @(
    "$taskMain/data/model/MainTab.kt",
    "$taskMain/data/system/CoreUpdatePolicy.kt",
    "$taskMain/data/system/CoreManagerUpdatePolicy.kt",
    "$taskMain/data/system/KpmProtocol.kt",
    "$taskMain/data/system/KpmDocument.kt",
    "$taskMain/data/system/KpmRepository.kt",
    "$taskMain/data/system/SusfsProtocol.kt",
    "$taskMain/ui/screen/kpm/KpmUiState.kt",
    "$taskMain/ui/screen/home/SusfsHomeInfo.kt",
    "$taskMain/ui/screen/home/HomeStartupState.kt",
    "$taskMain/ui/screen/home/HomeManagerUpdates.kt",
    "$taskMain/ui/util/module/LatestVersionInfo.kt"
)
$taskTests = Get-ChildItem -LiteralPath (Join-Path $taskManager 'app/src/test/java/io/milksu/manager/data/system') -Filter '*Test.kt'
$taskTests += Get-ChildItem -LiteralPath (Join-Path $taskManager 'app/src/test/java/io/milksu/manager/ui/screen/home') -Filter '*Test.kt'
$taskSources += $taskTests.FullName
$taskCompilerClasspath = @($taskCompiler, $taskStdlib, $taskScript, $taskReflect, $taskTrove, $taskAnnotations, $taskCoroutines) -join [IO.Path]::PathSeparator
$taskClasspath = @($taskStdlib, $taskAnnotations, $taskJson, $taskSerialization, $taskCoroutines, $taskJunit, $taskHamcrest) -join [IO.Path]::PathSeparator
& java -cp $taskCompilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 21 "-Xplugin=$taskPlugin" -classpath $taskClasspath -d $taskOutput @taskSources
if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed ($LASTEXITCODE)" }
$taskClasses = $taskTests | ForEach-Object {
    $taskPackage = [regex]::Match((Get-Content -LiteralPath $_.FullName -Raw), '(?m)^package\s+([\w.]+)').Groups[1].Value
    "$taskPackage.$($_.BaseName)"
}
& java -cp "$taskOutput$([IO.Path]::PathSeparator)$taskClasspath" org.junit.runner.JUnitCore @taskClasses
if ($LASTEXITCODE -ne 0) { throw "JUnit failed ($LASTEXITCODE)" }
& java -cp $taskCompilerClasspath (Join-Path $PSScriptRoot 'KotlinSyntaxCheck.java') $taskManager
if ($LASTEXITCODE -ne 0) { throw "Kotlin syntax check failed ($LASTEXITCODE)" }
