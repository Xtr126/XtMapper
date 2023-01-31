<p align="center">
<a href="#" target="_blank"><img src="/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png" width="100px" height="100px"/></a>
</p>

<h1 align="center">
  XtMapper
</h1>
<p align="center">
  XtMapper, a free and open source keymapper. <br>
  Play your Android games with keyboard and mouse on Android x86 9.0 +.
</p>

[![Build debug APK](https://github.com/Xtr126/XtMapper/actions/workflows/android.yml/badge.svg)](https://github.com/Xtr126/XtMapper/actions/workflows/android.yml)

<p align="center">
  <a href="https://github.com/Xtr126/XtMapper/releases">
     <img src="https://img.shields.io/github/downloads/Xtr126/XtMapper/total.svg?style=for-the-badge&logo=android" height="40px"/>
  </a>
  <a href="https://github.com/Xtr126/XtMapper/actions/workflows/android.yml">
      <img src="https://github.com/Xtr126/XtMapper/actions/workflows/android.yml/badge.svg" height="40px" />
  </a>
 </p>

## Features:

- Multi-touch emulation
- Emulate a touch pointer with mouse - Useful for games that only accept touch events and not mouse clicks.
- Emulate a D-pad with W,A,S,D or arrow keys
- Keyboard events to touch - And editing config with GUI
- Aim with mouse in FPS games (shooting mode) 
- Pinch-to-zoom gesture simulation using ctrl key + mouse
- Smooth pixel by pixel scrolling emulation for mouse wheel

APK can be obtained from GitHub actions (latest) or [`releases`](https://github.com/Xtr126/XtMapper/releases).

## Contributing

Pull requests for a bug fix or even a new feature are welcome.  
If you want to contribute code, please base your commits on the latest dev branch.  

Overview:
- Touch emulation is handled by TouchPointer.java and Input.java.  
- InputService.java runs separately from the app as an elevated java process using app_process and adb shell/root. 

## Useful discord servers 
BlissLabs: https://discord.com/invite/F9n5gbdNy2  
SupremeGamers: https://aopc.dev/discord  

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
