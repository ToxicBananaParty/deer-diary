@echo off
rem Thin wrapper so you don't have to activate the venv or type the full
rem .\.venv\Scripts\python.exe -m prism_sync invocation. Uses %~dp0 (the
rem directory containing this script) so it works regardless of cwd.
"%~dp0.venv\Scripts\python.exe" -m prism_sync %*
