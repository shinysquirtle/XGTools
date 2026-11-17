param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) { throw '请设置 JAVA_HOME 为 JDK 21 或通过 -JavaHome 指定。' }
[xml]$projectConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw
$appVersion = [string]$projectConfig.project.version
$jarName = ([string]$projectConfig.project.artifactId) + '-' + $appVersion + '.jar'
$jar = Join-Path $projectRoot ('target\' + $jarName)
if (-not (Test-Path -LiteralPath $jar)) { throw '请先运行 mvn clean package。' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$jar;$projectRoot\target\lib\*" cn.company.xgtools.Launcher
