<p align="center">
<a href="#" target="_blank"><img src="https://github.com/Xtr126/XtMapper/assets/80520774/2093a10b-f63f-4687-a4c9-d803f66d4e82" width="400px" height="400px"/></a>
</p>

<h1 align="center">
  XtMapper
</h1>
<p align="center">
  XtMapper, a free and open source keymapper. <br>
  Play your Android games with keyboard and mouse on Bliss OS
</p>

<p align="center">
  <a href="https://github.com/Xtr126/XtMapper/releases">
     <img src="https://img.shields.io/github/downloads/Xtr126/XtMapper/total.svg?style=for-the-badge&logo=android" height="30px"/>
  </a>
  <a href="https://github.com/Xtr126/XtMapper/actions/workflows/android.yml">
      <img src="https://github.com/Xtr126/XtMapper/actions/workflows/android.yml/badge.svg" height="20px" />
  </a>
 </p>

## Features:

- Multi-touch emulation
- Emulate a touch pointer with mouse - Useful for games that only accept touch events and not mouse clicks.
- Emulate a D-pad with W,A,S,D or arrow keys
- Keyboard events to touch - And editing config with GUI
- Aim with mouse in FPS games (shooting mode) 
- Pinch-to-zoom gesture using ctrl key + move mouse or ctrl + scroll wheel 
- Smooth pixel by pixel scrolling emulation for mouse wheel
- Swipes with keyboard keys
 
[Video Demonstration](https://www.youtube.com/watch?v=iK2OLMXRMTs)

## Development

### Build
- Run `./gradlew assembleDebug` at the base directory of the project 

If you want to contribute code, please base your commits on the latest dev branch.  

- Touch emulation is handled by TouchPointer.java and Input.java.  
- InputService.java runs separately from the app as an elevated java process using app_process and adb shell/root. 

## Help and support
Feel free to file an [issue](https://github.com/Xtr126/XtMapper/issues).  
Some useful discord servers (related):  
BlissLabs: https://discord.com/invite/F9n5gbdNy2  
AndroidEmu: https://discord.gg/mRpT4Qq  
SupremeGamers: https://aopc.dev/discord  

## Waydroid support
Tracking issue: 
https://github.com/Xtr126/XtMapper/issues/35  
Due to how XtMapper works by reading input events directly from the kernel, there are certain difficulties in implementing proper support for android containers.
An experimental solution was developed: https://github.com/Xtr126/wayland-getevent  


## Credits
Open source libraries used:
- [Material Design Components](https://github.com/material-components/material-components-android) used for the app user interface.
- [FloatingActionButtonSpeedDial](https://github.com/leinardi/FloatingActionButtonSpeedDial)

[Some code](./app/src/main/java/com/genymobile/scrcpy) from the [scrcpy](https://github.com/Genymobile/scrcpy) project was used for implementing multi-touch support in the keymapper. 

## Copyright and License
This project is licensed under the GPL v3.  
Do not publish unofficial APKs to the play store. 
```
XtMapper
Copyright (C) 2022 Xtr126

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 3.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License 
along with this program. If not, see https://www.gnu.org/licenses/.
```


