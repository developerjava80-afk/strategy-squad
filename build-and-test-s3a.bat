@echo off
echo ============================================================
echo  Session S3A — PositionPlanLeg + PositionPlan + PositionBuilderAgent
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
echo [2/3] Run PositionBuilderAgentTest...
call mvn -Dtest=PositionBuilderAgentTest test
if %ERRORLEVEL% neq 0 (
    echo PositionBuilderAgentTest FAILED.
    exit /b 1
)
echo PositionBuilderAgentTest OK.

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
echo  S3A build and test complete.
echo ============================================================
