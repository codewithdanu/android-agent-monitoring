# 📱 DeviceControl Mobile Agent (Android)

Android Foreground Service agent for background metrics collection, location tracking, and remote command execution.

## 🚀 Setup & Build

### 1. Requirements
- **Android Studio** (Koala or newer)
- **JDK 17+**
- **Android API 26+** (Android 8.0+)

### 2. Configuration
Open `app/src/main/kotlin/com/yourname/deviceagent/AgentConfig.kt` and update the server bridge:
```kotlin
object AgentConfig {
    const val SERVER_URL = "http://your-server-ip:3000" // Change this
}
```

### 3. Permissions Needed
Upon first run, the app will request:
- **Location (Fine/Coarse):** For GPS tracking.
- **Notifications:** Required for the Foreground Service.
- **Background Location:** (Manual Setup) Enable "Allow all the time" in Settings for offline tracking.
- **Device Administrator:** (Manual Setup) Required for the "Remote Lock" feature.

### 4. Installation
- Open the project in Android Studio.
- Sync Gradle.
- Click **Run** to install on your physical device or emulator.

---

## 🛠️ Logic & Actions
| Command | Action |
| :--- | :--- |
| **LOCK_SCREEN** | Uses DeviceAdmin to instantly lock the terminal. |
| **RING_ALARM** | Plays a high-volume tone (even if muted) until silenced. |
| **GET_LOCATION** | Forces a GPS refresh and pushes coords to dashboard. |
| **GET_BATTERY** | Pushes real-time battery health and level. |

## 🔄 Foreground Flow
1. **Initial Registration:** The user manually inputs the `Device ID` and `Token` on the main screen.
2. **Service Launch:** Clicking "Start Agent" triggers the `AgentService` (Foreground).
3. **Connectivity:** The agent connects to the `SERVER_URL` via Socket.io.
4. **Heartbeat:** Every 10 seconds, it sends system metrics (`MetricsHelper`).
5. **Passive Tracking:** Location is updated periodically based on distance/time intervals (`LocationHelper`).
6. **Command Listener:** The service waits for `command:new` events from the server and executes them via `CommandHandler`.
