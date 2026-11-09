@echo off
rem A host and a viewer window on a CMS-API of their own (see README.md), from the CMS-API checkout next to this repository.
call "%~dp0..\..\gradlew.bat" -q -p "%~dp0." installDist || (pause & exit /b 1)
call "%~dp0build\install\cms-p2p-test\bin\cms-p2p-test.bat" --local-demo "%~dp0..\..\..\CMS-API"
if errorlevel 1 pause
