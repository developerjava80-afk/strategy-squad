@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"
set "KITE_PROPS=%PROJECT_ROOT%\kite.properties"
set "LOG_FILE=%PROJECT_ROOT%\run\research-console.log"

set "PORT=8080"
if not "%~1"=="" set "PORT=%~1"

echo [info] Project root: %PROJECT_ROOT%
echo [info] Target port: %PORT%
if not exist "%PROJECT_ROOT%\run" mkdir "%PROJECT_ROOT%\run" >nul 2>&1
echo [info] Log file: %LOG_FILE%

call :KillPort "%PORT%"
if errorlevel 1 goto :ErrorClearPort

pushd "%PROJECT_ROOT%" >nul 2>&1
if errorlevel 1 goto :ErrorProjectRoot

where mvn >nul 2>&1
if errorlevel 1 goto :ErrorMaven

if not exist "%KITE_PROPS%" goto :ErrorKiteProps
call :RequireProperty "%KITE_PROPS%" "kite.api.key"
if errorlevel 1 goto :ErrorKitePropsField
call :RequireProperty "%KITE_PROPS%" "kite.api.secret"
if errorlevel 1 goto :ErrorKitePropsField
call :RequireProperty "%KITE_PROPS%" "kite.user.id"
if errorlevel 1 goto :ErrorKitePropsField

echo [info] Compiling backend...
call mvn -q -DskipTests compile
if errorlevel 1 goto :ErrorBuild

echo [info] Starting live research backend with daily login flow...
del /q "%LOG_FILE%" >nul 2>&1
start "StrategySquad-ResearchConsole-%PORT%" cmd /c "cd /d ""%PROJECT_ROOT%"" && mvn -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.strategysquad.ingestion.kite.KiteLiveConsoleMain >> ""%LOG_FILE%"" 2>&1"

call :WaitForReady "%PORT%" "300"
if errorlevel 1 goto :ErrorTimeout

set "UI_URL=http://localhost:%PORT%/login.html"
echo [ok] Research console is up.
echo [ok] Login URL: %UI_URL%
start "" "%UI_URL%"
echo %UI_URL%

popd >nul 2>&1
exit /b 0

:ErrorClearPort
echo [error] Failed while clearing port %PORT%.
exit /b 1

:ErrorProjectRoot
echo [error] Could not change directory to project root.
exit /b 1

:ErrorMaven
echo [error] Maven (mvn) is not available on PATH.
popd >nul 2>&1
exit /b 1

:ErrorKiteProps
echo [error] Missing %KITE_PROPS%.
echo [error] Create kite.properties before starting the live console.
echo [error] Use kite.properties.example as the template.
popd >nul 2>&1
exit /b 1

:ErrorKitePropsField
echo [error] kite.properties is missing one of the required fields:
echo [error]   kite.api.key
echo [error]   kite.api.secret
echo [error]   kite.user.id
echo [error] Use kite.properties.example as the template.
popd >nul 2>&1
exit /b 1

:ErrorBuild
echo [error] Build failed. Backend was not started.
popd >nul 2>&1
exit /b 1

:ErrorTimeout
echo [error] Backend did not become ready on port %PORT% within timeout.
if exist "%LOG_FILE%" (
    echo [error] Last backend log lines:
    powershell -NoProfile -Command "Get-Content -Path '%LOG_FILE%' -Tail 40"
)
popd >nul 2>&1
exit /b 1

:KillPort
set "KPORT=%~1"
set "FOUND=0"
for /f "tokens=5" %%P in ('netstat -aon ^| findstr :%KPORT% ^| findstr LISTENING') do (
    if not "%%P"=="" (
        if not "!SEEN_%%P!"=="1" (
            set "SEEN_%%P!=1"
            set "FOUND=1"
            echo [info] Killing PID %%P already listening on port %KPORT%...
            taskkill /F /PID %%P >nul 2>&1
        )
    )
)
if "!FOUND!"=="0" echo [info] Port %KPORT% is already free.
exit /b 0

:WaitForReady
set "WPORT=%~1"
set "WTIMEOUT=%~2"
set /a ELAPSED=0

:WaitLoop
set "OPEN=0"
for /f "tokens=5" %%P in ('netstat -aon ^| findstr :%WPORT% ^| findstr LISTENING') do set "OPEN=1"
if "!OPEN!"=="1" (
    powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing http://localhost:%WPORT%/api/auth/status -TimeoutSec 5; if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { exit 0 } else { exit 1 } } catch { exit 1 }"
    if not errorlevel 1 exit /b 0
)
if !ELAPSED! GEQ !WTIMEOUT! exit /b 1
set /a ELAPSED+=1
echo [info] Waiting for backend readiness... !ELAPSED!s
ping -n 2 127.0.0.1 >nul
goto WaitLoop

:RequireProperty
set "PROP_FILE=%~1"
set "PROP_NAME=%~2"
findstr /r /c:"^%PROP_NAME%=." "%PROP_FILE%" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0
