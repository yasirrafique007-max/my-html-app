# Bridge Live — HONOR X6c WhatsApp Call Assist

Personal-use Android 15 / MagicOS 9 prototype for Arabic ↔ English translation controls over WhatsApp.

## Setup on phone
1. Install the debug APK.
2. Grant microphone permission.
3. Enable **Bridge Live Call Assist** in Accessibility settings.
4. Grant Bridge Live **Notification access**.
5. Keep WhatsApp call notifications enabled.
6. When a WhatsApp call notification is detected, the floating translator appears.

## Translation buttons
- **Arabic → English**: listens with Android SpeechRecognizer using `ar-SA`, translates online, and speaks English.
- **English → Arabic**: listens with `en-GB`, translates online, and speaks Arabic.

## Android limitation
This is direct UI integration over WhatsApp, but it is not a private WhatsApp audio API. Android protects another app's VoIP downlink/uplink. On the HONOR X6c, if SpeechRecognizer cannot capture the remote caller while WhatsApp owns audio focus, use WhatsApp speakerphone mode so Bridge Live can attempt acoustic capture. Local Arabic TTS transmission also depends on MagicOS/WhatsApp echo cancellation.
