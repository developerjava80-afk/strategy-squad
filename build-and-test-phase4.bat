@echo off
REM ============================================================
REM Phase 4 — Scoped Scanner build + test script
REM ============================================================
REM Tests:
REM   ScannerQueryScopedTest        (6 tests — fetchScoped)
REM   MorningScannerServiceScopedTest (7 tests — scanScoped)
REM Regression:
REM   Phase1SmokeTest
REM ============================================================

echo.
echo === Phase 4: Scoped Scanner ===
echo.

echo [1/3] Compiling (skip tests)...
call mvn -DskipTests compile
if %ERRORLEVEL% neq 0 (
    echo.
    echo *** COMPILE FAILED ***
    exit /b %ERRORLEVEL%
)
echo     OK
echo.

echo [2/3] Running Phase 4 tests...
call mvn -Dtest=ScannerQueryScopedTest,MorningScannerServiceScopedTest test
if %ERRORLEVEL% neq 0 (
    echo.
    echo *** PHASE 4 TESTS FAILED ***
    exit /b %ERRORLEVEL%
)
echo     OK
echo.

echo [3/3] Regression — Phase1SmokeTest...
call mvn -Dtest=Phase1SmokeTest test
if %ERRORLEVEL% neq 0 (
    echo.
    echo *** REGRESSION FAILED — Phase1SmokeTest ***
    exit /b %ERRORLEVEL%
)
echo     OK
echo.

echo ============================================================
echo  Phase 4 DONE — all tests green.
echo ============================================================
