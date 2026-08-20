@echo off
setlocal enabledelayedexpansion

rem ============================================================
rem  DeepSeek Harness IDEA Plugin - One-click build script
rem  Double-click to build the plugin zip, then open the output dir.
rem ============================================================

rem ---- locate project root (script lives in scripts/, root is parent) ----
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."
pushd "%PROJECT_ROOT%"
set "PROJECT_ROOT=%CD%"
popd

echo ============================================
echo   DeepSeek Harness IDEA Plugin - Build
echo   Project : %PROJECT_ROOT%
echo ============================================
echo.

rem ---- 1. locate Java (IDE JBR 21 first, then JDK 17) ----
set "JAVA_HOME="
if exist "D:\develop\IntelliJ IDEA 2024.3.4.1\jbr" set "JAVA_HOME=D:\develop\IntelliJ IDEA 2024.3.4.1\jbr"
if defined JAVA_HOME goto :java_ok
if exist "D:\develop\Java\jdk-17" set "JAVA_HOME=D:\develop\Java\jdk-17"
if defined JAVA_HOME goto :java_ok
if defined DSH_JAVA_HOME if exist "%DSH_JAVA_HOME%" set "JAVA_HOME=%DSH_JAVA_HOME%"
if defined JAVA_HOME goto :java_ok
echo [ERROR] No Java found. Set JAVA_HOME (JBR 21 / JDK 17) and retry.
goto :fail
:java_ok
echo [1/3] Java       : %JAVA_HOME%
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- 2. locate Gradle user home (cache dir) ----
set "GRADLE_USER_HOME="
if exist "D:\develop\gradle-7.2\.gradle\repository" set "GRADLE_USER_HOME=D:\develop\gradle-7.2\.gradle\repository"
if defined GRADLE_USER_HOME goto :gradle_ok
if exist "%USERPROFILE%\.gradle" set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
if defined GRADLE_USER_HOME goto :gradle_ok
echo [WARN] No existing Gradle cache found; default location will be used.
:gradle_ok
echo [2/3] Gradle home : %GRADLE_USER_HOME%

rem ---- 3. verify Gradle distribution ----
if not exist "%PROJECT_ROOT%\tooling\gradle-8.14\bin\gradle.bat" goto :no_gradle
echo [3/3] Gradle     : %PROJECT_ROOT%\tooling\gradle-8.14\bin\gradle.bat
echo.

rem ---- 4. run the build (--no-daemon avoids stale-daemon cache locks) ----
echo Building... first run takes 1-7 minutes (bundles runtime), later runs are incremental.
echo.
call "%PROJECT_ROOT%\tooling\gradle-8.14\bin\gradle.bat" buildPlugin --console=plain --no-daemon
if errorlevel 1 goto :build_failed

rem ---- 5. locate the artifact ----
set "ZIP="
for /f "delims=" %%f in ('dir /b /o-d "%PROJECT_ROOT%\build\distributions\*.zip" 2^>nul') do (
    if not defined ZIP set "ZIP=%%f"
)
if not defined ZIP goto :no_zip

echo.
echo ============================================
echo   BUILD OK
echo   Package : %PROJECT_ROOT%\build\distributions\%ZIP%
echo ============================================
echo.
echo Install: Settings -^> Plugins -^> gear icon -^> Install Plugin from Disk...
echo Choose the zip above, then restart the IDE.
echo.
echo Open the output folder? (Y/N, default Y)
set "OPEN="
set /p OPEN=
if /i not "%OPEN%"=="N" (
    explorer "%PROJECT_ROOT%\build\distributions"
)
goto :end

:no_gradle
echo [ERROR] Gradle 8.14 not found: %PROJECT_ROOT%\tooling\gradle-8.14\bin\gradle.bat
goto :fail

:no_zip
echo [ERROR] No build artifact found at build\distributions\*.zip
goto :fail

:build_failed
echo.
echo [FAILED] Build did not succeed. See log above.
goto :fail

:fail
echo.
echo ============================================
echo   BUILD FAILED - check the log above
echo ============================================
pause
exit /b 1

:end
pause
exit /b 0
