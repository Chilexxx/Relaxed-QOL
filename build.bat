@echo off
setlocal
title Building Relaxed QOL for Minecraft 26.2

rem Minecraft 26.2 mods must be built with Java 25.
rem This finds your JDK 25 automatically. To use a different one, run: set "JDK25=C:\path\to\jdk" first.
if not defined JDK25 set "JDK25=%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
if not exist "%JDK25%\bin\java.exe" (
    for /d %%D in ("%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Java\jdk-25*") do (
        if exist "%%~D\bin\java.exe" set "JDK25=%%~D"
    )
)
if not exist "%JDK25%\bin\java.exe" (
    echo Java 25 was not found.
    echo Install it with:  winget install EclipseAdoptium.Temurin.25.JDK
    pause
    exit /b 1
)

set "JAVA_HOME=%JDK25%"
echo Using Java: %JAVA_HOME%
"%JAVA_HOME%\bin\java" -version

rem Stop old Gradle daemons that may still be running on Java 21
call gradlew.bat --stop >nul 2>&1

rem Passing java.home here overrides any old org.gradle.java.home line in a gradle.properties file
call gradlew.bat clean build "-Dorg.gradle.java.home=%JAVA_HOME%"
if errorlevel 1 (
    echo.
    echo BUILD FAILED - copy the error above and send it over.
    pause
    exit /b 1
)

echo.
echo BUILD SUCCESSFUL
echo Your mod: build\libs\relaxed-qol-1.0.0.jar
echo Put it in .minecraft\mods together with Fabric API for 26.2.
pause
