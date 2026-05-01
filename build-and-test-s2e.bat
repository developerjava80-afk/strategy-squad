@echo off
echo ============================================================
echo  Session S2E — Phase2ReplayTest (ENTER / HOLD / BOOK_PROFIT)
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
echo [2/3] Run Phase2ReplayTest...
call mvn -Dtest=Phase2ReplayTest test
if %ERRORLEVEL% neq 0 (
    echo Phase2ReplayTest FAILED.
    exit /b 1
)
echo Phase2ReplayTest OK.

echo.
echo [3/3] Run full test suite...
call mvn test
if %ERRORLEVEL% neq 0 (
    echo Full test suite FAILED.
    exit /b 1
)
echo All tests PASSED.

echo.
echo ============================================================
echo  S2E build and test complete.
echo ============================================================
