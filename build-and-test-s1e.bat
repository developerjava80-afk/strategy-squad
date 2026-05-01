@echo off
echo === Session S1E: SignalSnapshotService extraction + smoke test ===
echo Tasks: 1.7a, 1.7b, 1.8
echo.
cd /d %~dp0

echo --- Step 1: compile (skip tests) ---
call mvn -DskipTests compile
if %ERRORLEVEL% NEQ 0 (
    echo COMPILE FAILED
    exit /b 1
)
echo Compile OK
echo.

echo --- Step 2: run all tests ---
call mvn test
if %ERRORLEVEL% NEQ 0 (
    echo TESTS FAILED - check target/surefire-reports/
    exit /b 1
)
echo.
echo ALL TESTS PASSED - Session S1E complete
