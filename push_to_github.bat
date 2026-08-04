@echo off
REM ============================================================
REM  One-click push for the Coop Moves Forge port.
REM  1) Create an EMPTY public repo on GitHub (no README/.gitignore).
REM  2) Paste its URL below (replace the REPO_URL value).
REM  3) Double-click this file.
REM  On later changes just double-click again — it commits & pushes.
REM ============================================================

REM ==== EDIT THIS LINE: your repo URL ====
set REPO_URL=https://github.com/IlyshkaColeman/Shaking-hands-forge-1.20.1-47.4.4/actions
REM =======================================

cd /d "%~dp0"

where git >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Git is not installed. Get it from https://git-scm.com/ and run again.
    pause
    exit /b 1
)

if not exist ".git" (
    echo [*] Initializing repository...
    git init
    git branch -M main
    git remote add origin %REPO_URL%
) else (
    git remote set-url origin %REPO_URL% 2>nul || git remote add origin %REPO_URL%
)

REM Ensure a local commit identity exists (only for this repo)
git config user.name  "zxunger1"
git config user.email "zxunger1@users.noreply.github.com"

echo [*] Committing...
git add .
git commit -m "Coop Moves Forge port - update" || echo (nothing to commit)

echo [*] Pushing to %REPO_URL% ...
git push -u origin main

echo.
echo [DONE] If a login window appeared, sign in to GitHub in it.
echo Open your repo -> Actions tab to watch the build.
pause
