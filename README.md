# XtMapper
XtMapper is an free and open source keymapper application in development for Bliss OS.  
It can be used to play certain Android games that require a touchscreen, with keyboard and mouse.
# Currently working:
- Multi-touch emulation
- Emulate a touch pointer with mouse - Useful for games that only accept touch events and not mouse clicks.
- Keyboard events to touch - And editing config with GUI

More functionality will be added over time.  
Download a debug APK from GitHub actions.

# Notes:
- This app uses a patched getevent binary to read keyboard events. A pre-built binary is provided for simplicity. To build it from source yourself, check [getevent-patch](../getevent-patch/README.md).
