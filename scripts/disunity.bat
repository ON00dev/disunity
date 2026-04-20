@echo off
setlocal

set "BASEDIR=%~dp0"
set "JAR=%BASEDIR%disunity.jar"

if not exist "%JAR%" (
  for %%F in ("%BASEDIR%..\disunity-dist\target\disunity-dist-*-shaded.jar") do set "JAR=%%~fF"
)

if not exist "%JAR%" (
  echo Could not find disunity jar. Build it with: mvn -q clean package
  exit /b 1
)

java -jar "%JAR%" %*
