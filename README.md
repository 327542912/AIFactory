# AIFactory 0.1.0

A new, independent Android AI development workspace. It does not modify the existing EdgeCluster project.

## Included in this scaffold
- Kotlin + Jetpack Compose hybrid workspace UI
- Light/dark theme toggle
- Chat UI with configurable OpenAI-compatible `/chat/completions` endpoint
- Project, Terminal, Plugins and Settings pages
- Initial Termux RUN_COMMAND dispatch bridge

## Build in Termux

Extract the project to `~/AIFactory`, then run:

```bash
cd ~/AIFactory
gradle assembleDebug --no-daemon
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Install it with:

```bash
termux-open app/build/outputs/apk/debug/app-debug.apk
```

## Termux setup

In Termux:

```bash
mkdir -p ~/.termux
printf '\nallow-external-apps = true\n' >> ~/.termux/termux.properties
```

Restart Termux. Grant AIFactory the Termux RUN_COMMAND permission when Android presents it. Only authorize apps you trust.

Official interface reference: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

## Current limitations
- The bridge dispatches commands but does not yet stream stdout/stderr back into the app.
- API credentials are held in memory only and are not persisted. Do not use this scaffold on a shared device with sensitive credentials.
- This version is a functional UI/model-chat/command-dispatch scaffold, not yet a complete autonomous coding agent. Task DAG, constrained file tools, output streaming, build/test/repair loop, Git rollback, encrypted credential storage, plugin isolation, and self-update workflow remain to be implemented.
- Self-updating the host APK must use Android's package installer and user confirmation; silent self-replacement is not supported.

## Build versions
- compileSdk 34 / targetSdk 34 / minSdk 26
- Android Gradle Plugin 8.6.1
- Kotlin 2.0.21
- Intended for Gradle 8.7 and JDK 21
