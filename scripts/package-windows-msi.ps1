$PackageType = $args[0]
if ([string]::IsNullOrWhiteSpace($PackageType)) {
    $PackageType = "msi"
}
if ($PackageType -notin @("msi", "app-image")) {
    throw "Package type must be 'msi' or 'app-image'."
}

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

if ($env:OS -ne "Windows_NT") {
    throw "The MSI package can only be built on Windows."
}

$jpackage = Get-Command jpackage.exe -ErrorAction SilentlyContinue
if (-not $jpackage) {
    throw "A Java 25 JDK with jpackage.exe must be installed and available on PATH."
}

if ($PackageType -eq "msi" -and
    (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or
    -not (Get-Command light.exe -ErrorAction SilentlyContinue))) {
    throw "WiX Toolset 3.x (candle.exe and light.exe) is required on PATH to create an MSI."
}

[xml]$pom = Get-Content (Join-Path $projectRoot "pom.xml")
$projectVersion = $pom.project.version
$appVersion = ($projectVersion -replace "-.*$", "")
if ($appVersion -notmatch "^\d+(\.\d+){1,2}$") {
    throw "The Maven version '$projectVersion' cannot be converted to a Windows installer version."
}
if (($appVersion.Split(".")).Count -eq 2) {
    $appVersion += ".0"
}

$buildDirectory = Join-Path $projectRoot "target"
$jar = Join-Path $buildDirectory "radiology-companion-$projectVersion.jar"
$staging = Join-Path $projectRoot "target\windows-installer-input"
$destination = Join-Path $projectRoot "target\windows-$PackageType"
$appImage = Join-Path $destination "EliteradsPro"
$icon = Join-Path $projectRoot "src\main\resources\icon.ico"

& mvn -B -DskipTests package
if ($LASTEXITCODE -ne 0) {
    throw "Maven packaging failed with exit code $LASTEXITCODE."
}
if (-not (Test-Path $jar -PathType Leaf)) {
    throw "The expected application JAR was not created: $jar"
}

if (Test-Path $staging) {
    Remove-Item -LiteralPath $staging -Recurse -Force
}
New-Item -ItemType Directory -Path $staging -Force | Out-Null
New-Item -ItemType Directory -Path $destination -Force | Out-Null
if ($PackageType -eq "app-image" -and (Test-Path $appImage)) {
    Remove-Item -LiteralPath $appImage -Recurse -Force
}
Copy-Item -LiteralPath $jar -Destination $staging

$arguments = @(
    "--type", $PackageType,
    "--name", "EliteradsPro",
    "--app-version", $appVersion,
    "--vendor", "Eliterads Pro",
    "--description", "Eliterads Pro radiology workstation application",
    "--input", $staging,
    "--main-jar", (Split-Path -Leaf $jar),
    "--main-class", "com.radiology.MainLauncher",
    "--dest", $destination,
    "--icon", $icon,
    "--java-options", "--enable-native-access=ALL-UNNAMED"
)
if ($PackageType -eq "msi") {
    $arguments += @(
        "--win-per-user-install",
        "--win-menu",
        "--win-menu-group", "Eliterads Pro",
        "--win-shortcut",
        "--win-upgrade-uuid", "738df0df-15ae-4a56-a797-2b7c73b3ac30"
    )
}

& $jpackage.Source @arguments
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE."
}

Write-Host "Windows $PackageType package created under $destination"
Write-Host "The installer bundles a Java runtime. Microsoft Word is still required for DOCX-to-PDF conversion."
