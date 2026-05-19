@echo off
rem Double-clickable wrapper for the server SFTP-push deploy.
rem
rem Materializes the most recently published server pack into
rem .cache/server-resolved/ (downloads all Modrinth CDN refs, copies
rem self-hosted jars) and SFTP-syncs it to BloomHost's /mods/ directory.
rem Configs are NEVER touched - they're the server admin's domain.
rem
rem Restart the server via the BloomHost panel after this finishes to
rem load the new mods. The deploy itself doesn't trigger anything on
rem the server; it just stages the files.
rem
rem Prerequisite: run publish-server.bat (or publish-everything.bat)
rem first so docs/packwiz-server/ is the version you want to ship.

setlocal
title Deer Diary - server-deploy-to-bloomhost

rem Inject the SFTP password from the User-scope env var into this process
rem (setx persists for future sessions but doesn't reach already-open ones).
for /f "usebackq tokens=*" %%v in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('BLOOMHOST_SFTP_PASSWORD', 'User')"`) do set BLOOMHOST_SFTP_PASSWORD=%%v
if "%BLOOMHOST_SFTP_PASSWORD%"=="" (
    echo BLOOMHOST_SFTP_PASSWORD is not set in your User environment.
    echo Run: setx BLOOMHOST_SFTP_PASSWORD "your-bloomhost-sftp-password"
    echo Then close + reopen your terminal and try again.
    pause
    exit /b 1
)

cd /d "%~dp0prism-to-modrinth-sync"
call prism_sync.cmd server-deploy-to-bloomhost --yes
set EXITCODE=%ERRORLEVEL%

echo.
echo ============================================================
if %EXITCODE% EQU 0 (
    echo Deploy finished successfully ^(exit code 0^).
    echo Restart the server via the BloomHost panel to load the new mods.
) else (
    echo Deploy FAILED with exit code %EXITCODE%.
)
echo ============================================================
pause
