@echo off
rem Double-clickable wrapper for the server pack pipeline.
rem Runs `prism_sync server-publish --push --notify` from the prism-to-modrinth-sync
rem dir and pauses at the end so the console window stays open whether the
rem pipeline succeeds or fails - important for double-click use.
rem
rem Prerequisite: docs/packwiz/ must be up to date. If you haven't run
rem packwiz-build since the last client edit, this server build will
rem either reuse stale client metafiles or hard-error. Run publish-packwiz.bat
rem (or publish-everything.bat) first when in doubt.

setlocal
title Deer Diary - server-publish --push --notify
echo Running prism_sync server-publish --push --notify...
echo.

cd /d "%~dp0prism-to-modrinth-sync"
call prism_sync.cmd server-publish --push --notify
set EXITCODE=%ERRORLEVEL%

echo.
echo ============================================================
if %EXITCODE% EQU 0 (
    echo Server pipeline finished successfully ^(exit code 0^).
    echo Pack is live on GitHub Pages ^(~1-2 min^); restart server to apply.
) else (
    echo Server pipeline FAILED with exit code %EXITCODE%.
)
echo ============================================================
pause
