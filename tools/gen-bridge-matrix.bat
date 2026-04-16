@echo off
REM ═════════════════════════════════════════════════════════════════
REM gen-bridge-matrix.bat — Windows 래퍼
REM
REM 실제 로직은 gen-bridge-matrix.sh (bash 스크립트)에 있습니다.
REM 이 .bat는 bash가 %PATH%에 있으면 바로 호출합니다.
REM
REM 사용법:
REM   tools\gen-bridge-matrix.bat
REM ═════════════════════════════════════════════════════════════════

setlocal

REM bash 위치 탐색 순서: PATH → Git for Windows → WSL
where bash >nul 2>nul
if %errorlevel% equ 0 (
    bash "%~dp0gen-bridge-matrix.sh" %*
    goto :end
)

if exist "C:\Program Files\Git\bin\bash.exe" (
    "C:\Program Files\Git\bin\bash.exe" "%~dp0gen-bridge-matrix.sh" %*
    goto :end
)

if exist "C:\Program Files\Git\usr\bin\bash.exe" (
    "C:\Program Files\Git\usr\bin\bash.exe" "%~dp0gen-bridge-matrix.sh" %*
    goto :end
)

where wsl >nul 2>nul
if %errorlevel% equ 0 (
    wsl bash "%~dp0gen-bridge-matrix.sh" %*
    goto :end
)

echo [ERROR] bash를 찾을 수 없습니다. Git for Windows 또는 WSL을 설치해 주세요.
exit /b 1

:end
endlocal
