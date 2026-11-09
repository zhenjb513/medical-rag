@ECHO OFF
REM ----------------------------------------------------------------------------
REM Maven Wrapper - enhanced minimal version
REM ----------------------------------------------------------------------------

SETLOCAL ENABLEDELAYEDEXPANSION

SET "BASEDIR=%~dp0"
IF "%BASEDIR:~-1%"=="\" SET "BASEDIR=%BASEDIR:~0,-1%"

SET "WRAPPER_JAR=%BASEDIR%\.mvn\wrapper\maven-wrapper.jar"
SET "JAVACMD=java"

where java >NUL 2>NUL
IF ERRORLEVEL 1 (
    ECHO ERROR: Java is not installed or not in PATH
    ECHO Please install JDK 21 and try again
    EXIT /B 1
)

IF NOT EXIST "%WRAPPER_JAR%" (
    ECHO ERROR: maven-wrapper.jar not found at %WRAPPER_JAR%
    ECHO Re-run: mvn wrapper:wrapper
    EXIT /B 1
)

"%JAVACMD%" ^
  -Xms1024m ^
  -Xmx1024m ^
  -Dmaven.multiModuleProjectDirectory="%BASEDIR%" ^
  -classpath "%WRAPPER_JAR%" ^
  org.apache.maven.wrapper.MavenWrapperMain %*

ENDLOCAL