@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"
set "JDBC_URL=jdbc:postgresql://localhost:8812/qdb"
set "WINDOW_SECONDS=30"

if not "%~1"=="" set "WINDOW_SECONDS=%~1"
if not "%~2"=="" set "JDBC_URL=%~2"

pushd "%PROJECT_ROOT%" >nul 2>&1
if errorlevel 1 goto :ErrorProjectRoot

where mvn >nul 2>&1
if errorlevel 1 goto :ErrorMaven

echo [info] Checking live DB activity for the last %WINDOW_SECONDS% seconds...
call mvn -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.strategysquad.support.LiveDbRecentActivityMain -Dexec.args="%JDBC_URL% %WINDOW_SECONDS%"
set "EXIT_CODE=%ERRORLEVEL%"

popd >nul 2>&1
exit /b %EXIT_CODE%

:ErrorProjectRoot
echo [error] Could not change directory to project root.
exit /b 1

:ErrorMaven
echo [error] Maven (mvn) is not available on PATH.
popd >nul 2>&1
exit /b 1
