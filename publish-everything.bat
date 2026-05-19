@echo off
rem Double-clickable one-button release covering ALL three channels:
rem   1. Packwiz (client) -> GH Pages, bootstrap clients auto-update ~1-2 min
rem   2. Modrinth (client) -> 3-4 day review queue
rem   3. Server pack -> GH Pages, server applies on next approved restart
rem
rem Bails after step 1 if there are no changes in the live Prism instance
rem (the most common no-op case). Steps 2 and 3 always pass --allow-no-changes
rem so they re-publish off the state file just updated by their predecessor.
rem
rem Use publish-all.bat for the client-only flow (the old default), or
rem publish-server.bat for server-only.

setlocal
title Deer Diary - publish-everything
echo Publishing to Packwiz, Modrinth, AND server...
echo.

cd /d "%~dp0prism-to-modrinth-sync"

echo === [1/3] Packwiz publish ===
call prism_sync.cmd packwiz-publish --push
set PACKWIZ_EXIT=%ERRORLEVEL%

if %PACKWIZ_EXIT% NEQ 0 (
  echo.
  echo ============================================================
  echo Packwiz publish exit=%PACKWIZ_EXIT% ^- skipping Modrinth and server.
  echo If you saw "No changes since last publish" above, that's expected
  echo when the live instance hasn't changed since the last release.
  echo Close this window.
  echo ============================================================
  pause
  exit /b %PACKWIZ_EXIT%
)

echo.
echo === [2/3] Modrinth publish ===
call prism_sync.cmd publish --push --allow-no-changes
set MODRINTH_EXIT=%ERRORLEVEL%

echo.
echo === [3/3] Server publish ===
call prism_sync.cmd server-publish --push --notify --allow-no-changes
set SERVER_EXIT=%ERRORLEVEL%

echo.
echo ============================================================
echo Packwiz:  exit=%PACKWIZ_EXIT%  (live on GitHub Pages ~1-2 min)
echo Modrinth: exit=%MODRINTH_EXIT%  (queued for 3-4 day review)
echo Server:   exit=%SERVER_EXIT%  (published, NOT yet deployed to BloomHost)
echo ============================================================
echo.
echo The server pack is published to GitHub Pages, but BloomHost's mods/
echo directory still has whatever it had before. To deploy the new pack
echo to BloomHost, run deploy-server.bat (or `prism_sync server-deploy-to-bloomhost`).
echo BloomHost's locked Startup Command means we do the apply via SFTP
echo push from your local machine, not via a server-side update hook.
echo ============================================================
pause
