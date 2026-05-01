@echo off
echo === Session S2B: DecisionPolicy (Rules 1-5) — compile + test ===
cd /d %~dp0

call mvn -DskipTests compile
if %ERRORLEVEL% NEQ 0 (
    echo COMPILE FAILED
    exit /b 1
)
echo Compile OK

call mvn -Dtest=DecisionPolicyTest test
if %ERRORLEVEL% NEQ 0 (
    echo DecisionPolicyTest FAILED
    exit /b 1
)
echo DecisionPolicyTest OK

echo === Running full test suite ===
call mvn test
if %ERRORLEVEL% NEQ 0 (
    echo FULL TEST SUITE FAILED
    exit /b 1
)
echo All tests passed.
