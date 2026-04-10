# Notes-Android

This Android App is an AI-built project using GPT-5.4 in Codex, ported from [NotesFront (Vue3-Web)](https://github.com/simon-hale/NotesFrontend)

1. Environment

   - Open the project from the repository root: `your:\project\path\app`

   - Import with Android Studio using the included Gradle Wrapper

   - Install:
     - Android SDK Platform 35
     - Android SDK Platform 36.1


2. local.properties

   Create `local.properties` in the repository root:

   ```properties
   sdk.dir=your:\SDK\path
   notes.baseUrl=https://your-domain.example.com
   ```

   - `sdk.dir`: local Android SDK path

   - `notes.baseUrl`: backend base URL

   `local.properties` is local-only and must not be committed.
