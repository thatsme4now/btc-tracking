@echo off
setlocal

:: Depot Bitcoin Portfolio Launcher
:: Uses bundled Windows JRE - no Java installation required

set SCRIPT_DIR=%~dp0
set JRE=%SCRIPT_DIR%jre\bin\java.exe
set JAR=%SCRIPT_DIR%app\depot.jar

if not exist "%JRE%" (
    echo ERROR: Bundled JRE not found at %JRE%
    echo Please make sure you extracted the full ZIP archive.
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo ERROR: depot.jar not found at %JAR%
    pause
    exit /b 1
)

echo ======================================
echo  Depot Bitcoin Portfolio
echo  Starting server on port 8080...
echo ======================================
echo.

:: Start Spring Boot in background (new window, hidden after startup)
start "DepotBitcoin" /min "%JRE%" ^
    -Xmx256m ^
    -Dfile.encoding=UTF-8 ^
    -jar "%JAR%" ^
    --depot.db=inmemory

:: Wait for Spring Boot to initialize (~5 seconds)
echo Waiting for server to start...
timeout /t 5 /nobreak > nul

:: Open default browser
echo Opening browser...
start "" "http://localhost:8080/btc-tracking"

echo.
echo Server is running. Close the DepotBitcoin console window to stop.
endlocal