@echo off
REM Scout Map Server Deploy - Windows Helper
REM Run this from PowerShell as Administrator to extract and prepare the deploy package

set "SCRIPT_DIR=%~dp0"
set "DEPLOY_ROOT=%SCRIPT_DIR%.."
set "TARGET_DIR=%USERPROFILE%\scout-map-server-deploy"

echo Scout Map Server - Windows Preparation Helper
echo ============================================
echo.
echo This script copies the deploy package to your user profile
echo so you can transfer it to the target Debian machine via
echo Ventoy, USB, or network share.
echo.
echo Source: %DEPLOY_ROOT%
echo Target: %TARGET_DIR%
echo.

if exist "%TARGET_DIR%" (
    echo Target directory already exists.
    choice /C YN /M "Overwrite?"
    if errorlevel 2 goto :eof
    rmdir /s /q "%TARGET_DIR%"
)

xcopy /E /I /H /Y "%DEPLOY_ROOT%" "%TARGET_DIR%"

echo.
echo Done. Package ready at: %TARGET_DIR%
echo.
echo To deploy on Debian 13.6:
echo   1. Copy %TARGET_DIR% to the Debian machine (via Ventoy partition, USB, or scp)
echo   2. On Debian: cd /path/to/scout-map-server-deploy/scripts
echo   3. Run: sudo ./setup-map-server.sh
echo.
echo For offline deploy, copy 20260811.pmtiles (~16GB) to the Debian machine first, then:
echo   sudo ./setup-map-server.sh --pmtiles-path /path/to/20260811.pmtiles
echo.
pause