@echo off
rem Double-clickable wrapper for the prism sync release pipeline.
rem Runs `prism_sync publish --push` from the prism-to-modrinth-sync dir
rem and pauses at the end so the console window stays open whether the
rem pipeline succeeds or fails — important for double-click use.

setlocal
title Deer Diary - publish --push
echo Running prism_sync publish --push...
echo.

cd /d "%~dp0prism-to-modrinth-sync"
call prism_sync.cmd publish --push
set EXITCODE=%ERRORLEVEL%

echo.
echo ============================================================
if %EXITCODE% EQU 0 (
    echo Pipeline finished successfully ^(exit code 0^).
) else (
    echo Pipeline FAILED with exit code %EXITCODE%.
)
echo ============================================================
pause
