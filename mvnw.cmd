@ECHO OFF
REM Maven Wrapper - minimal version

SET "BASEDIR=%~dp0"
IF "%BASEDIR:~-1%"=="\" SET "BASEDIR=%BASEDIR:~0,-1%"

java -Xms1024m -Xmx1024m -Dmaven.multiModuleProjectDirectory="%BASEDIR%" -classpath "%BASEDIR%\.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*