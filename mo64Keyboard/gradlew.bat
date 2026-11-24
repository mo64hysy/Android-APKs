@echo off
set GRADLE_VERSION=8.9
set CACHE_DIR=%USERPROFILE%\.gradle\wrapper\dists\manual-gradle-%GRADLE_VERSION%
if not exist "%CACHE_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat" (
  mkdir "%CACHE_DIR%" 2>nul
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip -OutFile '%CACHE_DIR%\gradle.zip'; Expand-Archive -Force '%CACHE_DIR%\gradle.zip' '%CACHE_DIR%'"
)
call "%CACHE_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat" %*
