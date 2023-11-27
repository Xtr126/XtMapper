<p align="center">
<a href="#" target="_blank"><img src="https://github.com/Xtr126/XtMapper/assets/80520774/2093a10b-f63f-4687-a4c9-d803f66d4e82" width="400px" height="400px"/></a>
</p>

<h1 align="center">
  XtMapper
</h1>
<p align="center">
  XtMapper, a free and open source keymapper. <br>
  Play your Android games with keyboard and mouse on Bliss OS <br>
  https://xtr126.github.io/XtMapper-docs
</p>

<p align="center">
  <a href="https://github.com/Xtr126/XtMapper/releases">
     <img src="https://img.shields.io/github/downloads/Xtr126/XtMapper/total.svg?style=for-the-badge&logo=android" height="30px"/>
  </a>
  <a href="https://github.com/Xtr126/XtMapper/actions/workflows/android.yml">
      <img src="https://github.com/Xtr126/XtMapper/actions/workflows/android.yml/badge.svg" height="20px" />
  </a>
 </p>

## About

Introducing XtMapper, the ultimate keymapper for Android on PC gaming designed to revolutionize your gaming experience with unparalleled features that set it apart from the rest. It offers a comprehensive set of features unmatched by other keymapper available.

Unlike other keymappers that often inject ads or having users to pay, XtMapper is committed to providing a clean and user-friendly experience. With its extensive feature set, user-friendly interface, and commitment to it's userbase, XtMapper stands as the keymapper of choice for Android-x86 gaming enthusiasts. 

It is free and open-source software boasting a comprehensive array of functionalities that cater to Android-x86 users diverse needs for mouse/keyboard/touchpad input, making it a standout choice among keymapping applications that usually only target mobile phone hardware.

There is literally just no match for it among other Android keymappers in the context of Android-x86.  
As you might have already experienced, most other keymappers for Android are usually Adware / Paid closed source software that don't even support Android-x86 natively.  

XtMapper is included in Bliss OS and featured on [BlissLabs](https://blisslabs.org).

## Key Features

Multi-touch emulation from keyboard/mouse and enhancing control precision in various gaming scenarios: Providing a seamless and immersive touch experience for games that demand intricate touch inputs.

Mouse-to-touch emulation: Utilize your mouse as a touch pointer, enabling control in games that don't support direct mouse input.

Keyboard-to-touch emulation: Configure keyboard keys to trigger touch events, allowing you to play games that don't haven ative support for keyboard.

Aiming assistance: Enhance your aiming accuracy in FPS games with mouse-based aiming assistance while mapping mouse buttons and movement to various in-game functions.

Gesture emulation: Perform pinch-to-zoom gestures using keyboard shortcuts and mouse movements.

Smooth scrolling: Experience smooth and pixel-perfect scrolling emulation with your mouse wheel. Provides a more smooth and responsive scrolling experience than the android system defaults.

Swipe emulation: Execute swipes conveniently using designated keyboard keys, replicating touch-based gestures. Adds more versatility to your gaming controls.

Advanced touchpad support: Choose between direct touch or relative mode for touchpad input, catering to a variety of user preferences.

Low latency and high performance: Xtmapper provides low-latency, high-performance keymapping that ensures your inputs are registered with precision and speed. Enjoy a responsive and lag-free gaming experience.

Automatic profile switching: Automatically switch between keymapper profiles based on the active app, streamlining your gaming setup for each specific game.

Open-source development: Contribute to the ongoing development of XtMapper and benefit from its open-source nature.

XtMapper ensures a stable and reliable experience across most Android-x86 systems as long as it is Android 9 or newer. Older Android versions are not supported.

[Video Demonstration (outdated)](https://www.youtube.com/watch?v=iK2OLMXRMTs)

## Development

### Build
- Run `./gradlew assembleDebug` or `./gradlew.bat assembleDebug` at the base directory of the project 

- Touch emulation is handled by InputService.java and Input.java.  
- RemoteService.java runs separately from the app as an elevated java process using app_process and adb shell/root. 

## Help and support
Feel free to file an [issue](https://github.com/Xtr126/XtMapper/issues).  
You can ask about XtMapper on Bliss OS in  
BlissLabs discord server: https://discord.com/invite/F9n5gbdNy2  
Telegram: https://t.me/blissx86


## Waydroid support
Due to how XtMapper works by reading input events directly from the kernel, there are certain difficulties in implementing support for android containers. 
An experimental solution was developed: https://github.com/Xtr126/wayland-getevent 
It is mostly a "hack" that we have to rely on due to how wayland/waydroid works.  

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

