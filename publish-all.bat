@echo off
rem Double-clickable one-button release: Packwiz (GH Pages) THEN Modrinth.
rem
rem Step 1 publishes the live Prism instance to docs/packwiz/ and pushes,
rem so the bootstrap instance auto-updates within ~1-2 minutes.
rem Step 2 mirrors the same content to Modrinth (--allow-no-changes because
rem step 1 already updated the shared state file). Modrinth then takes
rem 3-4 days to review the new version.
rem
rem If step 1 says "No changes since last publish", the bat skips step 2.
rem That's intentional: nothing in the live instance changed, so neither
rem channel needs a new version.

setlocal
title Deer Diary - publish-all
echo Publishing to Packwiz (GH Pages) then Modrinth...
echo.

cd /d "%~dp0prism-to-modrinth-sync"

echo === [1/2] Packwiz publish ===
call prism_sync.cmd packwiz-publish --push
set PACKWIZ_EXIT=%ERRORLEVEL%

if %PACKWIZ_EXIT% NEQ 0 (
  echo.
  echo ============================================================
  echo Packwiz publish exit=%PACKWIZ_EXIT% ^- skipping Modrinth.
  echo If you saw "No changes since last publish" above, that's
  echo expected when the live instance hasn't changed since the
  echo last release. Close this window.
  echo ============================================================
  pause
  exit /b %PACKWIZ_EXIT%
)

echo.
echo === [2/2] Modrinth publish ===
call prism_sync.cmd publish --push --allow-no-changes
set MODRINTH_EXIT=%ERRORLEVEL%

echo.
echo ============================================================
echo Packwiz:  exit=%PACKWIZ_EXIT%  (live on GitHub Pages ~1-2 min)
echo Modrinth: exit=%MODRINTH_EXIT%  (queued for 3-4 day review)
echo ============================================================
pause
