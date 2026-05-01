@echo off
REM ============================================================
REM Phase 6 -- Scope-first: remove two-phase expansion
REM ============================================================
REM Changes:
REM   KiteLiveSessionManager  -- removed expandUniverse, expansionScheduler,
REM                              EXPANSION_DELAY_SECONDS, two-phase startSession.
REM                              Now starts ticker with empty subscription.
REM   KiteInstrumentsDumpJob  -- removed downloadAtmOnly()
REM   KiteInstrumentFilter    -- deprecated atmOnly() (no callers remain)
REM
REM Regression tests:
REM   Phase1SmokeTest
REM   ScannerQueryScopedTest
REM   MorningScannerServiceScopedTest
REM ============================================================

echo.
echo === Phase 6: Scope-first (remove two-phase expansion) ===
echo.

echo [1/2] Compiling (skip tests)...
call mvn -DskipTests compile
if %ERRORLEVEL% neq 0 (
    echo.
    echo *** COMPILE FAILED ***
    exit /b %ERRORLEVEL%
)
echo     OK
echo.

echo [2/2] Regression tests...
call mvn -Dtest=Phase1SmokeTest,ScannerQueryScopedTest,MorningScannerServiceScopedTest test
if %ERRORLEVEL% neq 0 (
    echo.
    echo *** REGRESSION FAILED ***
    exit /b %ERRORLEVEL%
)
echo     OK
echo.

echo ============================================================
echo  Phase 6 DONE -- scope-first design active.
echo ============================================================
echo.
echo  On next login, the ticker starts with 0 instrument subscriptions.
echo  Instruments are bound only when the user activates a scope
echo  via POST /api/scope in the UI scope picker.
echo ============================================================
