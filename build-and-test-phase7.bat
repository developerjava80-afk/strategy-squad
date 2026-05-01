@echo off
echo ============================================================
echo  Phase 7 — Guardrails + LiveStatus Gauges — Build ^& Test
echo ============================================================

echo.
echo [1/2] Compiling...
call mvn -DskipTests compile
if errorlevel 1 (
    echo COMPILE FAILED
    exit /b 1
)
echo Compile OK.

echo.
echo [2/2] Running regression tests...
call mvn -Dtest=Phase1SmokeTest,ScannerQueryScopedTest,MorningScannerServiceScopedTest test
if errorlevel 1 (
    echo TESTS FAILED
    exit /b 1
)
echo All tests passed.

echo.
echo ============================================================
echo  Phase 7 build ^& test PASSED
echo ============================================================
