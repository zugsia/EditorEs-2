<p align="center">
  <img src="./images/icon.png" alt="EditorEs" width="80" height="80"/>
</p>

<h2 align="center"><b>EditorEs</b></h2>
<p align="center">
  A C/C++ IDE for Android devices: edit, build with CMake + NDK, and run inside an Ubuntu (proot) environment.
</p>

<p align="center">
  <img src="https://github.com/lansky27/EditorEs-2/actions/workflows/build.yml/badge.svg" alt="Build">
  <img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License">
</p>

EditorEs started as a fork of [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE). The
Gradle/Java/XML tooling, Java language server, UI designer and Android project templates were removed
and replaced with a native C++ workflow.

## Features

- Code editor ([sora-editor](https://github.com/Rosemoe/sora-editor)) with tree-sitter highlighting
- C/C++ language support through `clangd` (completion, diagnostics, references, definitions,
  signature help, formatting)
- CMake workspaces with build presets (`cmake --list-presets`), build output panel
- Ubuntu 24.04 rootfs installed on first run and executed with proot
- Optional NDK and CMake toolchains, installed from the Backend settings screen
- Terminal ([Termux](https://github.com/termux/termux-app) emulator) opened inside the project
  directory
- Git clone, file tree, search in project

## How it works

| Piece | Where |
|---|---|
| Ubuntu rootfs download/extraction, proot configuration | `core/backend/.../proot` |
| NDK / CMake toolchain download, layout, pruning | `core/backend/.../build/Toolchain*.kt` |
| Build execution inside proot, preset parsing | `core/backend/.../build/BuildRunner.kt`, `CmakePresets.kt` |
| clangd LSP client | `core/app/.../lsp/cpp/CppLanguageServer.kt` |
| CMake workspace model | `core/projects` (`CppModule`, `IWorkspace`) |
| C++ project template | `utilities/templates-impl/.../cppExecutable` |
| Editor, tree-sitter, color schemes | `editor/*` |
| Terminal | `termux/*` |

Toolchains are downloaded from the `HomuHomu833/android-ndk-custom` and `HomuHomu833/cmake-custom`
GitHub releases (aarch64 builds). Only `arm64-v8a` devices can run the toolchains; the
`armeabi-v7a` APK is still produced for the editor/terminal.

## Building

Requirements: JDK 17, Android SDK (platform 34, build-tools 34.0.0) and NDK 26.1.10909125 (used by the
`termux/emulator` module's `ndkBuild`).

```
./gradlew :core:app:assembleDebug
./gradlew testDebugUnitTest test
```

`.hoplite/setup.sh` installs everything above on a fresh Debian/Ubuntu machine. CI
(`.github/workflows/build.yml`) builds signed release APKs for `arm64-v8a` and `armeabi-v7a`
on every push to `main`, and an unsigned debug APK for every pull request.

## Project layout

```
core/         app, actions, backend (proot + toolchains), common, lsp-api, lsp-models, projects, resources
editor/       api, impl, lexers (ANTLR C++ grammar), treesitter
event/        EventBus fork + typed events
logging/      logback-based logger
termux/       terminal emulator, view, shared utilities, terminal activity
utilities/    build-info, flashbar, lookup, preferences, shared, templates-api/impl, treeview
composite-builds/  build-logic plugins, source-built deps (appintro, fuzzysearch, logback-core)
```

## License

```
EditorEs is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

EditorEs is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with EditorEs.  If not, see <https://www.gnu.org/licenses/>.
```

Based on AndroidIDE, Copyright (C) Akash Yadav and contributors, GPL-3.0.
