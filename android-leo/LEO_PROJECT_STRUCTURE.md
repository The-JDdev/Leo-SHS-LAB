# LEO — SHS LAB | Android Agent App
**Target: API 29+ | Optimized for 2GB RAM**

---

## Project Structure

```
android-leo/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml              ← God-Mode permissions
│   │   ├── java/com/shslab/leo/
│   │   │   ├── LeoApplication.kt            ← App entry point, vault init
│   │   │   ├── MainActivity.kt              ← Pitch-black terminal UI
│   │   │   │
│   │   │   ├── security/
│   │   │   │   └── SecurityManager.kt       ← AES-256 GCM encrypted vault
│   │   │   │
│   │   │   ├── core/
│   │   │   │   ├── LeoProtocol.kt           ← Identity interceptor constant
│   │   │   │   ├── Logger.kt                ← Thread-safe terminal logger
│   │   │   │   └── BootReceiver.kt          ← Auto-start on device boot
│   │   │   │
│   │   │   ├── overlay/
│   │   │   │   └── OverlayService.kt        ← Dynamic Island bubble + kill switch
│   │   │   │
│   │   │   ├── accessibility/
│   │   │   │   └── LeoAccessibilityService.kt ← God-Mode UI control
│   │   │   │
│   │   │   ├── network/
│   │   │   │   └── LeoNetworkClient.kt      ← OkHttp AI client (all providers)
│   │   │   │
│   │   │   ├── parser/
│   │   │   │   └── CommandParser.kt         ← Regex JSON extractor + validator
│   │   │   │
│   │   │   ├── executor/
│   │   │   │   ├── ActionExecutor.kt        ← Central action router
│   │   │   │   └── CommandQueue.kt          ← 500ms throttle, priority queue
│   │   │   │
│   │   │   ├── file/
│   │   │   │   └── FileEngine.kt            ← Direct java.io.File /sdcard/ access
│   │   │   │
│   │   │   ├── git/
│   │   │   │   └── GitManager.kt            ← GitHub REST API + shell git commands
│   │   │   │
│   │   │   ├── cognitive/
│   │   │   │   └── CognitiveCleaner.kt      ← Deletion safety gate
│   │   │   │
│   │   │   └── shell/
│   │   │       └── ShellBridge.kt           ← ProcessBuilder shell executor
│   │   │
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml        ← Terminal UI layout
│   │       │   └── overlay_bubble.xml       ← Dynamic Island overlay
│   │       ├── xml/
│   │       │   └── accessibility_service_config.xml
│   │       ├── drawable/
│   │       │   ├── bubble_background.xml
│   │       │   ├── input_background.xml
│   │       │   ├── kill_switch_bg.xml
│   │       │   └── status_dot.xml
│   │       ├── anim/pulse.xml
│   │       ├── mipmap-*/ic_launcher.png     ← SHS LAB logo (all densities)
│   │       └── values/
│   │           ├── colors.xml               ← Cyan/Magenta/Yellow/Purple palette
│   │           ├── strings.xml
│   │           └── themes.xml               ← Pitch-black NoActionBar theme
│   │
│   ├── build.gradle
│   └── proguard-rules.pro
│
├── build.gradle
├── settings.gradle
└── gradle/wrapper/gradle-wrapper.properties
```

---

## First-Run Setup

1. **Open in Android Studio** — File → Open → select `android-leo/`
2. **Set GitHub token** in-app via the SecurityManager vault (or pre-configure):
   ```kotlin
   SecurityManager.store(SecurityManager.KEY_GITHUB_TOKEN, "your_token_here")
   ```
3. **Set AI API key** for your preferred provider:
   ```kotlin
   SecurityManager.store(SecurityManager.KEY_OPENROUTER_API, "sk-or-...")
   SecurityManager.store(SecurityManager.KEY_ACTIVE_PROVIDER, "openrouter")
   ```
4. **Grant permissions** on first launch: Storage, Overlay, Accessibility

---

## Color Palette (from SHS LAB logo)

| Color    | Hex       | Usage                   |
|----------|-----------|-------------------------|
| Cyan     | `#00BCD4` | Primary / terminal text |
| Magenta  | `#E91E8C` | Accents / prompts       |
| Yellow   | `#FFD600` | Nodes / warnings        |
| Purple   | `#6B35A5` | Center / Git ops        |

---

## Phase Completion

- **Phase 1** ✅ — Branding, Manifest, SecurityManager, Logger, Terminal UI, Overlay, Accessibility skeleton
- **Phase 2** ✅ — LeoNetworkClient, CommandParser, ActionExecutor, CommandQueue (2GB throttle), feedback loop
- **Phase 3** ✅ — FileEngine, GitManager, CognitiveCleaner, AccessibilityController (node traversal), ShellBridge
