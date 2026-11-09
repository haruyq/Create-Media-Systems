@echo off
rem One test window. CMS_P2P_API_URL and CMS_P2P_TEST_TOKEN, if set, fill in its API URL and token.
call "%~dp0..\..\gradlew.bat" -q -p "%~dp0." installDist || (pause & exit /b 1)
call "%~dp0build\install\cms-p2p-test\bin\cms-p2p-test.bat"
