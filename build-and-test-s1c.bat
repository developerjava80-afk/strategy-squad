@echo off
echo === Session S1C: mvn compile + test ===
cd /d %~dp0
call mvn -DskipTests compile
if %ERRORLEVEL% NEQ 0 (
    echo COMPILE FAILED
    exit /b 1
)
echo Compile OK
call mvn test
if %ERRORLEVEL% NEQ 0 (
    echo TESTS FAILED - check target/surefire-reports
    exit /b 1
)
echo ALL TESTS PASSED
