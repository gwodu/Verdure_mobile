# Verdure

**Personal AI Assistant for Android**

Verdure is an Android app that provides intelligent, privacy-first assistance through on-device AI processing. It acts as a "silent partner" that intelligently manages processes and notifications without being chatty or intrusive.

## Key Features

- **Privacy-first**: All AI processing happens on-device (no cloud API calls)
- **Smart notification prioritization**: Intelligent ranking based on importance and urgency
- **Calendar integration**: Day planner with upcoming events
- **Tool-based architecture**: Extensible system where capabilities are modularized
- **On-device LLM**: Qwen 3 0.6B running entirely on your phone via Cactus SDK

## Installation

### For Testers: 1-Click Install Link

**Coming soon:** Firebase App Distribution link for easy installation

Once configured, you'll get a shareable link like:
```
https://appdistribution.firebase.dev/i/abc123def456
```

Testers just click → sign in with Google → tap "Download" → install!

### For Developers: Build from Source

**Option 1: Download from GitHub Actions**
1. Go to https://github.com/gwodu/Verdure/actions
2. Click latest workflow run
3. Download `verdure-debug-apk` artifact
4. Install on your device

**Option 2: Local Build**
```bash
cd VerdureApp
./gradlew assembleDebug --no-daemon --max-workers=2
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Setting Up Firebase Distribution (For Maintainers)

**Want to enable 1-click install links for your testers?**

See **[DISTRIBUTION_QUICKSTART.md](DISTRIBUTION_QUICKSTART.md)** for a 15-minute setup guide.

Full documentation: [FIREBASE_SETUP.md](FIREBASE_SETUP.md)

## Setting Up the AI Model

Verdure uses **Qwen 3 0.6B** via the Cactus SDK. The model is downloaded on-device at runtime the first time the LLM is initialized.

### First Run Model Download

1. Install the APK from GitHub Actions.
2. Open Verdure on your device.
3. The app will download the model over the internet and initialize the LLM.

**Note:** This requires an internet connection and the `INTERNET` permission for the initial download.

## Permissions Required

1. **Notification Listener Access**: Required to read notifications
   - Settings → Apps → Special app access → Notification access → Enable for Verdure

2. **Calendar Access**: Required to read calendar events
   - Granted at runtime when you first open the app

3. **Internet Access**: Required to download the on-device model (first run only)

## Testing the AI

1. Open Verdure
2. Grant permissions
3. Send a message in the chat UI
4. You should see a real AI response from Qwen 3 running on your device

## Troubleshooting Model Download

- **Stuck on initialization**: Ensure the device has an internet connection and retry.
- **Download fails repeatedly**: Clear app storage (Settings → Apps → Verdure → Storage → Clear storage) and reopen the app.
- **No response after download**: Force close Verdure and reopen to reinitialize the model.

## Architecture

See [CLAUDE.md](CLAUDE.md) for detailed architecture documentation.

**Key components:**
- **LLMEngine**: Abstraction for swappable LLM backends (currently Cactus)
- **VerdureAI**: Central orchestrator that routes requests to tools
- **Tools**: Modular capabilities (NotificationTool, etc.)
- **Services**: Background data collection (VerdureNotificationListener, etc.)

## Development

- **Language**: Kotlin
- **Min SDK**: 31 (Android 12)
- **Target SDK**: 34 (Android 14)
- **Primary Device**: Google Pixel 8A

For development logs and session notes, see [DEVLOG.md](DEVLOG.md).

## License

(Add your license here)
