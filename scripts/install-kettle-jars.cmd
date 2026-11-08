@echo off
rem =====================================================================
rem 把 PDI 9.4 发行包 lib 目录下的 Kettle 核心 jar 安装到本地 Maven 仓库
rem (Pentaho 官方 Maven 仓库已改为认证访问，只能本地安装使用)
rem
rem 用法:
rem   install-kettle-jars.cmd                       使用 downloads\extracted 下的 jar
rem   install-kettle-jars.cmd D:\pdi\data-integration\lib    使用本地 PDI 安装的 lib
rem =====================================================================
setlocal enabledelayedexpansion

set "LIB_DIR=%~1"
if "%LIB_DIR%"=="" set "LIB_DIR=%~dp0..\downloads\extracted"
set "KETTLE_VERSION=9.4.0.0-343"

if not exist "%LIB_DIR%\kettle-core-%KETTLE_VERSION%.jar" (
  echo [错误] 在 %LIB_DIR% 下没有找到 kettle-core-%KETTLE_VERSION%.jar
  echo        请先运行 python scripts\fetch-kettle-jars.py fetch kettle-core-9.4 kettle-engine-9.4
  exit /b 1
)

echo 使用 jar 目录: %LIB_DIR%
echo.

call :install org.pentaho.di  kettle-core               kettle-core-%KETTLE_VERSION%.jar
call :install org.pentaho.di  kettle-engine             kettle-engine-%KETTLE_VERSION%.jar
call :install pentaho         metastore                 metastore-%KETTLE_VERSION%.jar
call :install org.pentaho     pdi-engine-api            pdi-engine-api-%KETTLE_VERSION%.jar
call :install org.pentaho     pentaho-registry          pentaho-registry-%KETTLE_VERSION%.jar
call :install org.pentaho     pentaho-encryption-support pentaho-encryption-support-%KETTLE_VERSION%.jar

echo.
echo 全部安装完成，回到项目根目录执行: mvn compile exec:java
exit /b 0

:install
mvn -q install:install-file -Dfile="%LIB_DIR%\%~3" -DgroupId=%~1 -DartifactId=%~2 -Dversion=%KETTLE_VERSION% -Dpackaging=jar
if errorlevel 1 (
  echo [失败] %~3
  exit /b 1
)
echo [OK] %~1:%~2:%KETTLE_VERSION%
exit /b 0
