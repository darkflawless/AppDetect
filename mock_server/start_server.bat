@echo off
chcp 65001 > nul
echo ======================================================================
echo 🚀 Đang khởi chạy AppDetect Mock Server (FastAPI + UDP 9090)...
echo ======================================================================

cd /d "%~dp0"

:: Mở trình duyệt sau 1.5 giây để Server kịp boot Uvicorn
start /b "" cmd /c "timeout /t 2 /nobreak > nul & start http://localhost:8080/static/dashboard.html"

:: Chạy server FastAPI
python server.py

pause
