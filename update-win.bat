@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

rem ===========================================================================
rem  Win 端一键更新：版本号不动（packageVersion 保持 desktop/build.gradle 里的值），
rem  把当前代码重新打包，覆盖装到 %LOCALAPPDATA%\Andee，用户数据一律不碰。
rem
rem  数据在 %USERPROFILE%\Library\Application Support\Andee（见 desktop 的
rem  Settings.kt），本来就不在安装目录里；这里既不清目录也不卸载，
rem  所以对话记录、笔记本、设置都原样留着。
rem
rem  更新顺序：
rem    1. 找 JDK（17-20，Gradle 8.4 的要求）
rem    2. 结束正在运行的 Andee（否则 exe/dll 被占用，写不进去）
rem    3. :desktop:packageMsi 打 MSI（版本号不变）
rem    4. msiexec 同版本强制重装：REINSTALL=ALL REINSTALLMODE=vomus
rem       —— 同版本的 ProductCode 也是同一个，普通 /i 只会进"维护模式"，
rem          加上这两个属性才是真正把文件覆盖一遍
rem    5. MSI 这一步失败（没装 WiX、msiexec 报错）→ 回落到目录覆盖：
rem         打 :desktop:createDistributable，robocopy /E 覆盖安装目录
rem    6. 重新启动 Andee
rem
rem  只增量构建，不 clean；不跑测试。日志：
rem    %TEMP%\andee-win-update.log / andee-win-gradle.log / andee-win-msi.log
rem ===========================================================================

cd /d "%~dp0"

set "APP_EXE=Andee.exe"
set "INSTALL_DIR=%LOCALAPPDATA%\Andee"
set "DATA_DIR=%USERPROFILE%\Library\Application Support\Andee"
set "GRADLE_LOG=%TEMP%\andee-win-gradle.log"
set "MSI_LOG=%TEMP%\andee-win-msi.log"
set "UPDATE_LOG=%TEMP%\andee-win-update.log"

echo ════════════════════════════════════════
echo   Andee Win 一键更新（不升版本号）
echo   装到  %INSTALL_DIR%
echo   数据  %DATA_DIR%
echo ════════════════════════════════════════
echo.

rem ---------- 1. JDK 17-20 --------------------------------------------------
rem 探测期间先清掉 JAVA_TOOL_OPTIONS：java -version 会为它多打一行
rem "Picked up ..."，把版本行挤走
set "JDK_HOME="
set "JAVA_TOOL_OPTIONS="
if defined JAVA_HOME call :probe_jdk "%JAVA_HOME%"

if not defined JDK_HOME (
  for %%c in (
    "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.1+1"
    "C:\Program Files\Eclipse Adoptium\jdk-17"
    "C:\Program Files\Java\jdk-17"
    "C:\Program Files\Microsoft\jdk-17"
  ) do if not defined JDK_HOME call :probe_jdk "%%~c"
)

if not defined JDK_HOME (
  for /d %%d in (
    "C:\Program Files\Eclipse Adoptium\jdk-17*"
    "C:\Program Files\Eclipse Adoptium\jdk-18*"
    "C:\Program Files\Eclipse Adoptium\jdk-19*"
    "C:\Program Files\Eclipse Adoptium\jdk-20*"
    "C:\Program Files\Java\jdk-17*"
    "C:\Program Files\Java\jdk-20*"
  ) do if not defined JDK_HOME call :probe_jdk "%%~d"
)

if not defined JDK_HOME (
  echo [x] 没找到 JDK 17-20。Gradle 8.4 跑不了 21+，也跑不了 16 及以下。
  echo     装一个 JDK 17 后重试，或者在 shell 里先把 JAVA_HOME 指过去。
  exit /b 1
)

set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8"
echo [1/5] JDK   %JAVA_HOME%

rem ---------- 2. 结束正在运行的 Andee ---------------------------------------
tasklist /FI "IMAGENAME eq %APP_EXE%" 2>nul | find /i "%APP_EXE%" >nul
if not errorlevel 1 (
  echo [2/5] 结束正在运行的 Andee
  taskkill /IM "%APP_EXE%" /F >nul 2>&1
  rem jpackage 的 launcher 退出后文件句柄还要一小会儿才放开
  ping -n 3 127.0.0.1 >nul
) else (
  echo [2/5] Andee 没在运行
)

rem ---------- 3. 打 MSI -----------------------------------------------------
echo [3/5] 打包 MSI（增量，约 1-3 分钟）...
call gradlew.bat :desktop:packageMsi --console=plain > "%GRADLE_LOG%" 2>&1
set "RC=!ERRORLEVEL!"
if not "!RC!"=="0" (
  echo       打包失败（退出码 !RC!），最后 40 行：
  call :tail "%GRADLE_LOG%" 40
) else (
  echo       完成
)

rem 产物按名字找最新的一个：版本号不变，所以文件名不变，靠修改时间区分
set "MSI="
for /f "delims=" %%f in ('dir /b /a-d /o-d "desktop\build\compose\binaries\main\msi\*.msi" 2^>nul') do (
  if not defined MSI set "MSI=%CD%\desktop\build\compose\binaries\main\msi\%%f"
)
if defined MSI echo       MSI   %MSI%

rem ---------- 4. 装上去 -----------------------------------------------------
set "INSTALLED="
if "!RC!"=="0" if defined MSI (
  echo [4/5] msiexec 覆盖安装
  if exist "%INSTALL_DIR%\%APP_EXE%" (
    rem 同版本重装：光 /i 会进维护模式，这两个属性才强制把文件写一遍
    msiexec /i "%MSI%" /qn /norestart REINSTALL=ALL REINSTALLMODE=vomus /l*v "%MSI_LOG%"
  ) else (
    rem 这台机器上还没用 MSI 装过：走全新安装
    msiexec /i "%MSI%" /qn /norestart /l*v "%MSI_LOG%"
  )
  set "RC=!ERRORLEVEL!"
  if not "!RC!"=="0" if not "!RC!"=="3010" (
    rem 1605/1638 之类：装的不是这个 ProductCode，普通安装才是对的路
    echo       覆盖重装返回 !RC!，改走常规安装再试一次
    msiexec /i "%MSI%" /qn /norestart /l*v "%MSI_LOG%"
    set "RC=!ERRORLEVEL!"
  )
  if "!RC!"=="0" set "INSTALLED=msi"
  if "!RC!"=="3010" set "INSTALLED=msi"
  if not defined INSTALLED (
    echo       MSI 安装失败（退出码 !RC!），日志 %MSI_LOG%
  )
)

if not defined INSTALLED (
  echo [4/5] MSI 这条路没走通，回落到目录覆盖
  call :copy_over
  if errorlevel 1 (
    echo [x] 更新失败，安装目录没被改动。
    exit /b 1
  )
  set "INSTALLED=copy"
)

rem ---------- 5. 启动 -------------------------------------------------------
if not exist "%INSTALL_DIR%\%APP_EXE%" (
  echo [x] 装完了但找不到 %INSTALL_DIR%\%APP_EXE%，请手动确认。
  exit /b 1
)
echo [5/5] 启动 %INSTALL_DIR%\%APP_EXE%
start "" "%INSTALL_DIR%\%APP_EXE%"

echo.
echo 更新完成（方式 !INSTALLED!）。
if exist "%DATA_DIR%" (
  echo 数据目录未改动：%DATA_DIR%
) else (
  echo 数据目录还没生成，首次运行后才会有：%DATA_DIR%
)
exit /b 0

rem ===========================================================================
rem  子过程
rem ===========================================================================

rem 这个目录里的 java 是不是 17-20；是就把 JDK_HOME 设上。
rem 先把 -version 落到临时文件再 findstr：直接写
rem   for /f ... in ('"路径里带空格\java.exe" -version ^| findstr /c:"version"')
rem 的话，cmd /c 会把整行的首尾引号当成外包装剥掉（引号超过两个时走的是
rem 「去头去尾」那条规则），命令就成了缺引号的残句。
:probe_jdk
set "CAND=%~1"
if "%CAND%"=="" exit /b 1
if not exist "%CAND%\bin\java.exe" exit /b 1
set "JDK_MAJOR="
"%CAND%\bin\java.exe" -version > "%TEMP%\andee-jdk-version.txt" 2>&1
for /f "tokens=3" %%v in ('findstr /i /c:"version" "%TEMP%\andee-jdk-version.txt"') do (
  for /f "tokens=1 delims=." %%a in ("%%~v") do set "JDK_MAJOR=%%a"
)
if not defined JDK_MAJOR exit /b 1
if !JDK_MAJOR! LSS 17 exit /b 1
if !JDK_MAJOR! GTR 20 exit /b 1
set "JDK_HOME=%CAND%"
exit /b 0

rem MSI 走不通时的兜底：把可分发目录覆盖到安装目录。
rem 先确保目录存在——packageMsi 失败在 WiX 那一步时，createDistributable 其实
rem 已经跑完了，这里只是给没跑过的情况补一刀。
:copy_over
set "IMG=desktop\build\compose\binaries\main\app\Andee"
if not exist "%IMG%\%APP_EXE%" (
  echo       先打 createDistributable ...
  call gradlew.bat :desktop:createDistributable --console=plain > "%GRADLE_LOG%" 2>&1
  if errorlevel 1 (
    echo       打包失败，最后 40 行：
    call :tail "%GRADLE_LOG%" 40
    exit /b 1
  )
)
if not exist "%IMG%\%APP_EXE%" (
  echo       没找到 %IMG%\%APP_EXE%
  exit /b 1
)
rem /E 只增不删：安装目录里没有用户数据，但多删一次风险不值得冒
robocopy "%IMG%" "%INSTALL_DIR%" /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP > "%UPDATE_LOG%" 2>&1
set "RC=!ERRORLEVEL!"
if !RC! GEQ 8 (
  echo       robocopy 失败（退出码 !RC!），日志 %UPDATE_LOG%
  exit /b 1
)
echo       已覆盖到 %INSTALL_DIR%
exit /b 0

rem 打印文件最后 N 行（失败时给出能看懂的那一段）
:tail
powershell -NoProfile -Command "Get-Content -LiteralPath '%~1' -Tail %~2 -ErrorAction SilentlyContinue"
exit /b 0
