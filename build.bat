@echo off
setlocal

cd /d "%~dp0"

echo === OwnClaw Build ===

rem Always prefer JDK 21 (required by OwnClaw)
for /d %%D in ("%USERPROFILE%\.jdk\jdk-21*") do set "JAVA_HOME=%%D"
if defined JAVA_HOME echo Using JAVA_HOME=%JAVA_HOME%

call gradlew.bat build -x test %*

echo.
echo Build complete. JAR: build\libs\ownclaw-0.1.0.jar
