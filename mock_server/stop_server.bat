@echo off
chcp 65001 > nul
echo ======================================================================
echo 🛑 Đang tắt tiến trình Mock Server (Python port 8080)...
echo ======================================================================

for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8080') do (
    echo Đang dừng tiến trình PID: %%a
    taskkill /F /PID %%a > nul 2>&1
)

echo ✅ Đã tắt Server thành công!
pause
