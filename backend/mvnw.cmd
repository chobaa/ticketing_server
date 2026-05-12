@ECHO OFF
SETLOCAL

SET BASE_DIR=%~dp0
SET WRAPPER_DIR=%BASE_DIR%\.mvn\wrapper
SET PROPS_FILE=%WRAPPER_DIR%\maven-wrapper.properties

IF NOT EXIST "%PROPS_FILE%" (
  ECHO Missing "%PROPS_FILE%"
  EXIT /B 1
)

FOR /F "usebackq tokens=1,* delims==" %%A IN ("%PROPS_FILE%") DO (
  IF "%%A"=="wrapperUrl" SET WRAPPER_URL=%%B
)

IF "%MAVEN_USER_HOME%"=="" SET MAVEN_USER_HOME=%USERPROFILE%\.m2

SET WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar

IF NOT EXIST "%WRAPPER_JAR%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$p='%WRAPPER_JAR%';$u='%WRAPPER_URL%';" ^
    "New-Item -ItemType Directory -Force -Path (Split-Path $p) | Out-Null;" ^
    "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
    "Invoke-WebRequest -UseBasicParsing -Uri $u -OutFile $p"
)

java -classpath "%WRAPPER_JAR%" -Dmaven.multiModuleProjectDirectory="%BASE_DIR%" org.apache.maven.wrapper.MavenWrapperMain %*

ENDLOCAL

