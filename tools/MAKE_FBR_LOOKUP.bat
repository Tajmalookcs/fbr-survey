@echo off
cd /d "%~dp0"
echo Building FBR_Lookup.fbr (CNIC, name, business name only) from db.sqlite3 ...
echo.
if exist "venv\Scripts\python.exe" (
    "venv\Scripts\python.exe" make_fbr_lookup.py
) else (
    python make_fbr_lookup.py
)
echo.
pause
