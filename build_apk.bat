@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================================
echo   邻家账本 一键打包脚本
echo ============================================================
echo.

rem ---- 1. 检查 Java ----
set "JDK_FOUND="
where java >nul 2>nul && set "JDK_FOUND=1"
if NOT defined JDK_FOUND (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JDK_FOUND=1"
        set "PATH=%JAVA_HOME%\bin;%PATH%"
    )
)
if NOT defined JDK_FOUND (
    rem 常见 Android Studio 自带 JDK 位置
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JDK_FOUND=1" & set "PATH=C:\Program Files\Android\Android Studio\jbr\bin;%PATH%" & set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
if NOT defined JDK_FOUND (
    echo [错误] 没找到 Java。请先安装 JDK 17 或 Android Studio（自带 JBR），
    echo        并设置 JAVA_HOME 后重试。
    echo.
    pause
    exit /b 1
)

rem ---- 2. 检查本地 SDK 配置 ----
if not exist "local.properties" (
    echo [提示] 未找到 local.properties，将尝试自动定位 Android SDK...
    if defined ANDROID_HOME (
        echo sdk.dir=%ANDROID_HOME:\=/%>local.properties
    ) else if exist "%LOCALAPPDATA%\Android\Sdk" (
        echo sdk.dir=%LOCALAPPDATA:\=/%/Android/Sdk>local.properties
    ) else (
        echo [错误] 未找到 Android SDK。请先安装 Android Studio 并在其中打开一次本工程，
        echo        让 SDK 下载完成；或手动建立 local.properties 写入 sdk.dir=你的SDK路径
        echo.
        pause
        exit /b 1
    )
)

rem ---- 3. 生成 wrapper 并打包 ----
if exist "gradlew.bat" (
    if exist "gradle\wrapper\gradle-wrapper.jar" (
        echo [1/2] 使用现有 Gradle Wrapper 打包...
        call gradlew.bat assembleDebug
        goto :done
    )
)

where gradle >nul 2>nul
if %errorlevel%==0 (
    echo [1/2] 生成 Gradle Wrapper...
    call gradle wrapper --gradle-version 8.9
    echo [2/2] 执行打包 assembleDebug...
    call gradlew.bat assembleDebug
    goto :done
)

echo [错误] 既没有 wrapper.jar 也没有 gradle 命令。
echo        请直接用 Android Studio 打开本工程，菜单 Build -^> Build APK。
echo.
pause
exit /b 1

:done
echo.
echo ============================================================
echo   完成！APK 位置：
echo   app\build\outputs\apk\debug\app-debug.apk
echo   把它发到手机按「安装到手机-打包安装步骤.txt」操作即可。
echo ============================================================
echo.
start "" "app\build\outputs\apk\debug"
pause