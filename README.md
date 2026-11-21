# AIFactory Engineering Workbench

Independent Android engineering-workbench prototype. All changes in this branch are isolated from `main` and from the separate EdgeCluster project.

## Current implementation in this branch

- Jetpack Compose Android application with chat, project, file, terminal, plugin and settings pages.
- Android Storage Access Framework directory import into the app-private project workspace. Import is bounded (2,000 files, 25 MB per file, 200 MB total, 24 directory levels); common generated folders and `.DS_Store` entries are skipped; project dotfiles such as `.github` and `.gitignore` are preserved.
- Project inventory and basic build-system detection for Gradle, Maven, CMake, Python and Node.js.
- UTF-8 project file reader/editor with canonical-path containment checks, per-file size limits and before-write checkpoints.
- Checkpoint rollback for individual file changes.
- A constrained project build/test runner that does not invoke a shell and only accepts exact allowlisted command forms.
- Persistent task DAG ledger with dependency-cycle validation, legal state transitions, event history and interrupted-task marking.\n- OpenAI-compatible tool-calling loop for listing projects, scanning project structure, reading bounded UTF-8 source files and proposing complete-file changes. Proposals are stored separately and require the user to load the full text into the editor and press Save before source is changed. Build/test tools are exposed only when the user enables the explicit opt-in.
- Android CI workflow is configured to build a debug APK and upload it as an Actions artifact; the latest source revision is still awaiting a successful CI build.

## Important runtime limitations

This is **not yet a complete autonomous coding agent** and must not be described as one.

- The current terminal uses Android `/system/bin/sh`; the app-private runtime folders are not a Linux distribution.
- No embedded Linux rootfs, package manager, JDK, Gradle distribution, Python, Node.js, CMake or compiler toolchain is bundled.
- Project build/test execution therefore succeeds only if the required executable is already available to the Android app process and the project wrapper is executable. A normal Termux installation is not automatically visible to this app.
- The task DAG persistence layer is implemented but is not yet connected to a model-driven planner/executor loop.
- The agent can inspect projects through structured tool calls, submit complete-file change proposals for human approval, and optionally invoke the fixed build/test runner. It still has no unattended patch application or failure-repair loop; each proposed file must be reviewed and saved by the user in the checkpointed editor.
- Text/source attachments are read with per-file and total size caps when explicitly sent; images, PDF and Office content are not yet parsed.\n- The app does not yet provide a foreground task service, reliable process continuation after Android kills the app, multi-agent orchestration, plugin sandboxing, encrypted credential vault, APK signing/release workflow or self-update.
- The imported project is a copy inside the app's private storage. It does not modify the original selected directory.

## Build

The branch CI workflow uses JDK 21, Android SDK platform 34 and Gradle 8.7:

```bash
gradle assembleDebug --no-daemon --max-workers=2
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## Safety notes

- Model output is not executed as shell commands.
- Project paths are canonicalized and constrained to the selected project root.
- File writes create a checkpoint before replacing the target.
- The build/test runner accepts a fixed set of command forms and does not pass command strings through a shell.
- Import and source editing operate on the app-private copy, not on the original source tree.
