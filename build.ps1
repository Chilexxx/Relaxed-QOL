# Builds Relaxed QOL (Minecraft 26.2) and copies the jar into your mods folder.
#
# Put this file in the project folder (next to gradlew.bat) and run, in PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
#
# Options:
#   -NoCopy            only build, don't touch the mods folder
#   -ModsDir <path>    a different mods folder (default: %APPDATA%\.minecraft\mods)
#   -Jdk <path>        a specific JDK 25 folder

param(
    [switch]$NoCopy,
    [string]$ModsDir = (Join-Path $env:APPDATA ".minecraft\mods"),
    [string]$Jdk = ""
)

# "Continue": java and gradle print normal output on stderr, which Windows PowerShell 5.1 would treat as a crash
$ErrorActionPreference = "Continue"
$Host.UI.RawUI.WindowTitle = "Building Relaxed QOL for Minecraft 26.2"
Set-Location -Path $PSScriptRoot

function Fail($message) {
    Write-Host ""
    Write-Host $message -ForegroundColor Red
    Read-Host "Press Enter to close"
    exit 1
}

# ---------------------------------------------------------------- find Java 25
function Test-Jdk25($path) {
    if (-not $path) { return $false }
    $java = Join-Path $path "bin\java.exe"
    if (-not (Test-Path $java)) { return $false }
    $version = (cmd /c "`"$java`" -version 2>&1" | Select-Object -First 1) -as [string]
    return ($version -match '"25[\."]')
}

$candidates = @()
if ($Jdk) { $candidates += $Jdk }
if ($env:JDK25) { $candidates += $env:JDK25 }
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
foreach ($root in @(
    "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
    "$env:ProgramFiles\Eclipse Adoptium",
    "$env:ProgramFiles\Java",
    "$env:ProgramFiles\Microsoft",
    "$env:ProgramFiles\Zulu",
    "$env:ProgramFiles\BellSoft")) {
    if (Test-Path $root) {
        $candidates += Get-ChildItem -Path $root -Directory -Filter "*25*" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName }
    }
}

$jdkHome = $candidates | Where-Object { Test-Jdk25 $_ } | Select-Object -First 1
if (-not $jdkHome) {
    Fail ("Java 25 (JDK) was not found.`n" +
          "Install it with:  winget install EclipseAdoptium.Temurin.25.JDK`n" +
          "or run this script with -Jdk ""C:\path\to\jdk-25""")
}

$env:JAVA_HOME = $jdkHome
$env:Path = "$jdkHome\bin;$env:Path"
Write-Host "Using Java: $jdkHome" -ForegroundColor Cyan
& "$jdkHome\bin\java.exe" -version

# ---------------------------------------------------------------- build
if (-not (Test-Path ".\gradlew.bat")) {
    Fail "gradlew.bat not found - put build.ps1 in the project folder (next to build.gradle)."
}

# Old Gradle daemons may still run on an older Java
& .\gradlew.bat --stop *> $null

Write-Host ""
Write-Host "Building... (the first build downloads Minecraft and Fabric, that takes a few minutes)" -ForegroundColor Cyan
& .\gradlew.bat clean build "-Dorg.gradle.java.home=$jdkHome"
if ($LASTEXITCODE -ne 0) {
    Fail "BUILD FAILED - scroll up for the first error and send it over."
}

# ---------------------------------------------------------------- find the jar
$jar = Get-ChildItem -Path ".\build\libs" -Filter "relaxed-qol-*.jar" |
    Where-Object { $_.Name -notmatch "-(sources|dev)\.jar$" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { Fail "Build finished but no jar was found in build\libs." }

Write-Host ""
Write-Host "Built: $($jar.FullName)" -ForegroundColor Green

# ---------------------------------------------------------------- copy to mods
if (-not $NoCopy) {
    if (-not (Test-Path $ModsDir)) { New-Item -ItemType Directory -Path $ModsDir | Out-Null }
    # Remove older versions so two copies of the client are never loaded
    @("relaxed-qol-*.jar") | ForEach-Object { Get-ChildItem -Path $ModsDir -Filter $_ -ErrorAction SilentlyContinue } |
        Where-Object { $_.Name -ne $jar.Name } |
        ForEach-Object {
            Write-Host "Removing old $($_.Name)" -ForegroundColor DarkGray
            Remove-Item $_.FullName -Force
        }
    Copy-Item -Path $jar.FullName -Destination $ModsDir -Force
    Write-Host "Copied to: $ModsDir" -ForegroundColor Green
    Write-Host "Make sure Fabric API 0.161.0+26.2 is in that folder too." -ForegroundColor Yellow
}

Write-Host ""
Read-Host "Done - press Enter to close"
