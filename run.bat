@echo off
setlocal

cd /d "%~dp0"

set "JAR=build\libs\ownclaw-0.1.0.jar"

rem Build if JAR doesn't exist
if not exist "%JAR%" (
    echo JAR not found, building first...
    call build.bat
    if errorlevel 1 exit /b 1
)

rem Always prefer JDK 21 from ~/.jdk (required by OwnClaw)
set "JAVA=java"
for /d %%D in ("%USERPROFILE%\.jdk\jdk-21*") do set "JAVA=%%D\bin\java.exe"

if not defined OWNCLAW_PORT set "OWNCLAW_PORT=8080"

echo === OwnClaw ===
echo WebUI: http://localhost:%OWNCLAW_PORT%
echo Press Ctrl+C to stop
echo.

"%JAVA%" -jar "%JAR%" --server.port=%OWNCLAW_PORT% %*
