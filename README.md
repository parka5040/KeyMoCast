# KeyMoCast

This is a project that creates a remote mouse and keyboard solution on your Android smartphone that connects to a desktop running my custom WebSocket server. This was designed initially so I could control the desktop I have in my living room from my couch without having to buy a mouse that can connect via 2.4GHz from that far away. So basically it was born out of laziness.

Though, this was great as while I have experience in C#, I've never done this much Kotlin before. Great learning experience. It works and the Releases page has both the APK and the executable for the app and the server respectively.

In the future, I'll actually add a non-default logo for this app and make the solution more seamless.

## Features

### Mouse Control
- Single finger move for cursor movement
- Single tap for left click
- Double tap for double-click
- Two-finger tap for right click
- Two-finger move for scrolling
- Adjustable sensitivity and acceleration -> Go into settings

### Keyboard Input
- Real-time text input
- Special key support (Backspace, Enter, etc.)
- If your keyboard for some reason can't do backspace or enter, there are button options provided (as a failsafe)

### Security
- PIN-based authentication (4-digit)
- Only runs on your local network
- TLS encryption


## System Requirements

### Desktop (Server)
- Windows 10/11
- .NET 8.0
- 100MB available storage
- Network adapter with WiFi or Ethernet

### Android (Client)
- Android 7.0 (API 24) or higher

## Installation

### Desktop Application
1. Download the executable (or compile it yourself using the provided Powershell script)
2. Run it
3. Follow on-screen instructions. It should provide a pin and IP address

### Android Application
1. Download the APK (or compile it yourself on Android Studio)
2. Run it
3. (Optional) Go into settings and set the IP address that shows up on the desktop executable
4. Enter the pin (it autosaves assuming you don't shut down the server)
5. Wait for connection confirmation and then play


## Dependencies

### Desktop Application
- InputSimulatorCore - Input simulation
- Fleck - WebSocket server
- Newtonsoft.Json - JSON handling
- System.Configuration - Settings management

### Android Application
- Kotlin Coroutines - Asynchronous programming
- OkHttp - WebSocket client
- Material Design Components - UI elements
- AndroidX Core KTX - Kotlin extensions

## Future Plans
- Refactor it to be more maintainable
- Create multi-server connections (so you can swap between different desktops if need be)
- Add Smart-TV functionality
