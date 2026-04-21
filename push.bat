@echo off
set PATH=D:\Trial\PortableGit\bin;%PATH%
cd /d D:\Trial\Scrcpy\Clipboard\ClipboardBridge
git add app\src\main\java\com\clipboardbridge\ClipboardActivity.java
git commit -m "Fix Shizuku - use BinderWrapper + reflection"
git push
pause