@echo off
set PATH=D:\Trial\PortableGit\bin;%PATH%
cd /d D:\Trial\Scrcpy\Clipboard\ClipboardBridge
git add -A
git commit -m "Update"
git push
echo.
echo === Push 完成，去 Actions 等 build ===
pause