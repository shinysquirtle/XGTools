param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('app-image','exe','msi')][string]$Type = 'app-image'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) { throw '请设置 JAVA_HOME 为 JDK 21。' }
$engineRoot = Join-Path $projectRoot 'vendor\ghostscript'
foreach ($required in @('bin\gswin64c.exe','bin\gsdll64.dll','lib','Resource','doc\COPYING')) {
    if (-not (Test-Path -LiteralPath (Join-Path $engineRoot $required))) { throw "内置引擎不完整：$required" }
}
[xml]$projectConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw
$appVersion = [string]$projectConfig.project.version
$jarName = ([string]$projectConfig.project.artifactId) + '-' + $appVersion + '.jar'
$jar = Join-Path $projectRoot ('target\' + $jarName)
if (-not (Test-Path -LiteralPath $jar)) { throw '请先运行 mvn clean package。' }
$inputDirectory = Join-Path $projectRoot ('target\package-input-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination $inputDirectory
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'target\lib') -Filter '*.jar' | Copy-Item -Destination $inputDirectory
$destination = Join-Path $projectRoot ('dist\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$packager = Join-Path $JavaHome 'bin\jpackage.exe'
$icon = Join-Path $projectRoot 'src\main\resources\icons\xgtools.ico'
if (-not (Test-Path -LiteralPath $icon)) { throw '应用图标不存在，请运行 scripts/generate-icon.ps1。' }
& $packager --type app-image --name XGTools --app-version $appVersion --vendor 'Internal Tools' --icon $icon --input $inputDirectory --main-jar $jarName --main-class cn.company.xgtools.Launcher --dest $destination
if ($LASTEXITCODE -ne 0) { throw '生成应用目录失败' }
$imageDirectory = Join-Path $destination 'XGTools'
Copy-Item -LiteralPath $engineRoot -Destination (Join-Path $imageDirectory 'ghostscript') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD-PARTY-NOTICES.md') -Destination $imageDirectory
& (Join-Path $imageDirectory 'ghostscript\bin\gswin64c.exe') --version
if ($LASTEXITCODE -ne 0) { throw '内置引擎启动自检失败' }
if ($Type -ne 'app-image') {
    & $packager --type $Type --name XGTools --app-version $appVersion --vendor 'Internal Tools' --app-image $imageDirectory --icon $icon --dest $destination --win-dir-chooser --win-menu --win-shortcut --win-per-user-install
    if ($LASTEXITCODE -ne 0) { throw '安装包生成失败；检查当前 JDK 所要求的 WiX 工具是否安装。' }
}
Write-Output "本机测试版本：$imageDirectory\XGTools.exe"
