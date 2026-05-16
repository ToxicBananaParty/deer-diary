@echo off
rem Double-clickable wrapper for the Packwiz release pipeline.
rem Runs `prism_sync packwiz-publish --push` from the prism-to-modrinth-sync
rem dir and pauses at the end so the console window stays open whether the
rem pipeline succeeds or fails - important for double-click use.

setlocal
title Deer Diary - packwiz-publish --push
echo Running prism_sync packwiz-publish --push...
echo.

cd /d "%~dp0prism-to-modrinth-sync"
call prism_sync.cmd packwiz-publish --push
set EXITCODE=%ERRORLEVEL%

echo.
echo ============================================================
if %EXITCODE% EQU 0 (
    echo Packwiz pipeline finished successfully ^(exit code 0^).
) else (
    echo Packwiz pipeline FAILED with exit code %EXITCODE%.
)
echo ============================================================
pause
