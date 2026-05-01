@echo off
echo ============================================================
echo  Phase 3 — ScopeService + Atomic Subscription Swap
echo ============================================================

cd /d "%~dp0"

echo.
echo [1/3] Compile (skip tests)...
call mvn -DskipTests compile
if %ERRORLEVEL% neq 0 (
    echo COMPILE FAILED. Fix errors before running tests.
    exit /b 1
)
echo Compile OK.

echo.
echo [2/3] Run Phase 3 unit tests...
call mvn -Dtest=KiteSubscriptionManagerTest,ScopeServiceTest test
if %ERRORLEVEL% neq 0 (
    echo Phase 3 unit tests FAILED.
    exit /b 1
)
echo Phase 3 tests PASSED.

echo.
echo [3/3] Run full test suite to catch regressions...
call mvn test
if %ERRORLEVEL% neq 0 (
    echo Full test suite FAILED.
    exit /b 1
)
echo All tests PASSED.

echo.
echo ============================================================
echo  Phase 3 build and test complete.
echo ============================================================
echo.
echo  DoD check:
echo    - KiteSubscriptionManager owns the subscribed token set (AtomicReference)
echo    - KiteTickerSession.doPoll() takes one atomic snapshot per cycle
echo    - ScopeService.activate() persists + swaps subscription atomically
echo    - POST /api/scope  -> 200 {status, instrumentCount, truncated}
echo    - GET  /api/scope  -> 200 {activeScope: ...} or {activeScope: null}
echo    - DELETE /api/scope -> 200 {status: "deactivated"}
echo.
