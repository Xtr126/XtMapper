# XtMapper

[![Build debug APK](https://github.com/Xtr126/XtMapper/actions/workflows/android.yml/badge.svg)](https://github.com/Xtr126/XtMapper/actions/workflows/android.yml)

XtMapper is an free and open source keymapper application in development for Bliss OS.  
It can be used to play certain Android games that require a touchscreen, with keyboard and mouse.

# Currently working:

- Multi-touch emulation
- Emulate a touch pointer with mouse - Useful for games that only accept touch events and not mouse clicks.
- Emulate a D-pad with W,A,S,D or arrow keys
- Keyboard events to touch - And editing config with GUI

More functionality will be added over time.  
Download a debug APK from GitHub actions.

# Contributing

Pull requests for a bug fix or even a new feature are welcome.  
If you want to contribute code, please base your commits on the latest dev branch.  

Overview:
- Touch emulation is handled by TouchPointer.java and Input.java.  
- Input.java runs separately from the app as an elevated java process using app_process and adb shell/root, check Server.java for more details. 

# Notes:

- This app uses a patched getevent binary to read keyboard events. A pre-built binary is provided for simplicity. To build it from source yourself, check [getevent-patch](../getevent-patch/README.md).
