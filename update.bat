@echo off
set PATH=D:\Trial\PortableGit\bin;%PATH%
cd /d %~dp0
git add -A
git commit -m "Update"
git push
echo.
echo === Push 完成，去 Actions 等 build ===
pause