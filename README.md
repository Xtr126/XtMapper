<p align="center">
<a href="#" target="_blank"><img src="https://github.com/Xtr126/XtMapper/assets/80520774/2093a10b-f63f-4687-a4c9-d803f66d4e82" width="300px" height="300px"/></a>
</p>

<h1 align="center">
  XtMapper
</h1>
<p align="center">
  XtMapper, a free and open source keymapper. <br>
  Play your Android games with keyboard and mouse on Bliss OS <br>
</p>

<p align="center">
  <a href="https://github.com/Xtr126/XtMapper/releases">
     <img src="https://img.shields.io/github/downloads/Xtr126/XtMapper/total.svg?style=for-the-badge&logo=android" height="30px"/>
  </a>
 </p>

## About and features
https://xtr126.github.io/XtMapper-docs/guides/about  
[Watch video on YouTube](https://www.youtube.com/watch?v=Slcu43xBV3M)  

## Development

### Build
- Run `./gradlew assembleDebug` or `./gradlew.bat assembleDebug` at the base directory of the project 

## Help and support
Feel free to file an [issue](https://github.com/Xtr126/XtMapper/issues).  
You can ask about XtMapper on Bliss OS in  
BlissLabs discord server: https://discord.com/invite/F9n5gbdNy2  
Telegram: https://t.me/blissx86

## Waydroid support
Due to how XtMapper works by reading input events directly from the kernel, there are certain limitations in implementing support for Android containers. 
An experimental solution was developed: https://github.com/Xtr126/wayland-getevent 
It is mostly a "hack" that we have to rely on due to how wayland/waydroid works.  

## Credits
Open source libraries used:

- [Material Design Components](https://github.com/material-components/material-components-android) - User interface
- [FloatingActionButtonSpeedDial](https://github.com/leinardi/FloatingActionButtonSpeedDial) - Controls in editor
- [libsu](https://github.com/topjohnwu/libsu) - RootService  
- [Logo](https://github.com/Xtr126/XtMapper/assets/80520774/2093a10b-f63f-4687-a4c9-d803f66d4e82) - Made with [Blender](https://www.blender.org/)

[Some code](./app/src/main/java/com/genymobile/scrcpy) from the [scrcpy](https://github.com/Genymobile/scrcpy) project was used for implementing multi-touch support in the keymapper.  
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"
     alt="Get it on IzzyOnDroid"
     height="80">](https://apt.izzysoft.de/fdroid/index/apk/xtr.keymapper)
## Copyright and License
The source code is licensed under the GPL v3.  
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

