@echo off
chcp 65001 >nul
cd /d "C:\Users\16039\WorkBuddy\记账APP"
set "JAVA_HOME=C:\Users\16039\.workbuddy\binaries\jdk\17.0.2"
echo 正在启动滴水记账（桌面端）...
call gradlew.bat :desktop:run --console=plain --no-daemon
echo.
echo 窗口已关闭，按任意键退出
pause >nul
