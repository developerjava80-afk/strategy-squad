@echo off
REM ============================================================
REM Session S2C build-and-test script
REM Deliverables: DecisionPolicy rules 6-10, DecisionPolicyTest
REM               rules 6-10, RiskGuardService stub, RiskGuardInput
REM ============================================================

echo [S2C] Step 1: compile (skip tests)
call mvn -DskipTests compile
if errorlevel 1 (
    echo [S2C] COMPILE FAILED - fix errors before proceeding
    exit /b 1
)

echo [S2C] Step 2: run DecisionPolicyTest only
call mvn -Dtest=DecisionPolicyTest test
if errorlevel 1 (
    echo [S2C] DecisionPolicyTest FAILED
    exit /b 1
)

echo [S2C] Step 3: run full test suite
call mvn test
if errorlevel 1 (
    echo [S2C] Full test suite FAILED
    exit /b 1
)

echo [S2C] ALL STEPS PASSED
