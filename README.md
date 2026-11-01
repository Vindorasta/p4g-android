# Persona 4 Golden for Android (Native Port)

An experimental Android project designed to run **Persona 4 Golden (Nintendo Switch version)** natively on ARM64 smartphones and tablets.

The project uses a custom Nintendo Switch compatibility runtime, native ARM64 execution (NCE), Android system integrations, and Vulkan-based graphics.

Because Nintendo Switch and modern ARM64 Android devices share the AArch64 instruction set, parts of the game's original executable can run directly on the device's CPU. The runtime reimplements the Nintendo Switch services and APIs required by the game.

**No PC required for game preparation:** the Android application supports selecting a game dump and encryption keys directly on the phone.

> [!WARNING]
> **EXPERIMENTAL — NOT YET PLAYABLE**
>
> Persona 4 Golden is currently in active development.
>
> The application can recognize and load a supported game dump, initialize native modules, prepare shader data, and begin executing the game's original code.
>
> However, the game does **not yet reach the title screen or render gameplay**.
>
> Crashes and compatibility issues are expected. This repository should not currently be considered a playable Android port.

> [!IMPORTANT]
> **No copyrighted game files are distributed.**
>
> This repository and its APK builds do not include Persona 4 Golden's original executables, assets, encryption keys, firmware, or other copyrighted game content.
>
> Users must provide their own legally obtained game dump and the necessary keys.
>
> Game files and encryption keys are not provided or linked here. Instructions for obtaining them are outside the scope of this project.

Persona 4 Golden is © ATLUS / © SEGA.

This is an unofficial fan-made technical project and is not affiliated with, sponsored by, or endorsed by ATLUS, SEGA, or Nintendo.

---

## Installation

### Experimental Android builds

1. Download the latest available APK from [GitHub Actions](../../actions/workflows/build-p4g.yml).
2. Install the APK on an ARM64 Android device.
3. Open the application and grant the required file permissions.
4. Select your own Persona 4 Golden Nintendo Switch game dump.
5. Select your own `prod.keys`.
6. Run the game's preparation process.
7. Use **Test P4G Startup** to test the current native runtime.

**The startup test is intended for debugging, not gameplay.** At the current development stage, the game may show a blank screen, return to the launcher, or crash.

The application supports exporting diagnostic logs, which are useful for identifying runtime compatibility issues.

### Tested game version

| Property | Value |
|---|---|
| Game | Persona 4 Golden |
| Platform | Nintendo Switch |
| Title ID | `010062B01525C000` |
| Region | USA |
| Tested version | Base game, v0 |
| Build ID | `C433D980688380A642C086072F620928DF7D6320000000000000000000000000` |
| Execution architecture | ARM64 / AArch64 |
| Status | Experimental, not playable |

Other game revisions and regions have not yet been validated.

---

## Requirements

The following hardware and software capabilities are relevant to development and testing:

- ARM64 Android smartphone or tablet.
- Android system with native AArch64 execution support.
- Vulkan-capable graphics hardware.
- Sufficient memory for native module loading and runtime allocations.
- Storage for the user's own Nintendo Switch game dump and generated preparation data.
- User-provided game encryption keys.

### Development test device

| Device | Android | Result |
|---|---|---|
| Samsung Galaxy A35 5G (SM-A356E) | Android 16 | Game modules load and execution reaches `nnMain`; startup currently crashes |

Minimum RAM requirements, graphics compatibility, and gameplay performance have **not yet been established**.

---

## Development Status

<escape>### Working or partially verified</escape>

- Android launcher and game file selection.
- User-provided key handling.
- Recognition of the tested Persona 4 Golden NSP.
- Reading the game's RomFS from its game image.
- Loading the `main`, `sdk`, and `subsdk0` executable modules.
- Resolving imports and relocating native modules.
- Native AArch64 execution through the NCE runtime.
- Initialization of several Nintendo Switch system services.
- Mounting the game's RomFS and save directory.
- Entering `nn::init::Start`.
- Entering the game's `nnMain`.
- Extracting GXP shader assets for experimental processing.
- Experimental GXP-to-SPIR-V conversion pipeline.
- Native debugging, crash reporting, and log export.

### Currently being investigated

- Unresolved native imports.
- Incomplete Nintendo Switch system service implementations.
- Application and display layer initialization.
- Null-pointer crashes during game startup.
- Compatibility between the existing runtime and Persona 4 Golden's executable.
- Shader and renderer integration.
- GPU initialization and Vulkan presentation.

### Not yet verified

- Title screen.
- Main menu.
- In-game graphics.
- Audio playback.
- Cutscenes.
- Battles.
- Saving and loading within gameplay.
- Stable frame rates.
- Touch and physical controller support during gameplay.
- Complete gameplay.

**Important:** successfully loading the game executable or processing shader files does not mean the game is playable.

---

## How It Works

### Native Execution (NCE)

The Android application loads the game's executable modules from the user's Nintendo Switch game dump.

The runtime maps these modules into memory, applies relocations, resolves imported symbols, and begins executing their original ARM64 instructions.

Some Nintendo Switch-specific operations require compatibility handling, including:

- Supervisor calls (`SVC`).
- Thread-local storage and thread-pointer behavior.
- System timers and counters.
- Kernel services.
- Inter-process communication (IPC).
- Application lifecycle management.

The native execution implementation is located in:

`runtime/src/nce/`

### Nintendo Switch Runtime

The project builds upon a Nintendo Switch compatibility runtime originally developed for other native game ports.

It provides components for:

- Memory management.
- Kernel services.
- File system access.
- Account services.
- System time.
- Application management.
- Input handling.
- Display services.
- Graphics compatibility.
- Native execution and error reporting.

Relevant source files include:

```text
runtime/src/main.cpp
runtime/src/loader.cpp
runtime/src/memory.cpp
runtime/src/ipc.cpp
runtime/src/hle_fs.cpp
runtime/src/hle_vi.cpp
runtime/src/svc_am.cpp
runtime/src/svc_time.cpp
runtime/src/nce/
```

### Game Files

The runtime reads the user's game dump through the game image subsystem.

The implementation includes support for locating executable modules and accessing the game's RomFS.

Relevant code:

```text
runtime/src/game_image.cpp
runtime/src/game_image.h
```

### Shader Preparation

Persona 4 Golden contains shader-related data that the current preparation pipeline can extract and process.

The project includes an experimental native library named:

`libp4gshader.so`

This library uses a GXP-to-SPIR-V conversion approach based on Vita3K's shader compiler.

The preparation pipeline has been exercised with approximately **11,462 extracted GXP shader programs**.

**This shader conversion is experimental.** GXP assets and Nintendo Switch NVN/Maxwell shaders are not interchangeable, and successful conversion does not establish that the resulting shaders can render the Switch version correctly.

Additional work is required to establish a functioning graphics pipeline.

Relevant code:

```text
games/p4g/import/
games/p4g/import/p4g_shaders.cpp
```

---

## Current Startup Progress

The native runtime has reached the game's original entry point.

Example diagnostic output:

```text
p4g: main build ... recognized

loaded main
loaded sdk
loaded subsdk0

linked: 176 to HLE,
41755 across modules,
22 unresolved

entering nn::init::Start

sdk constructors: 112
main constructors: 25

p4g: runtime initialized
entering nnMain

fs: mounted app0:
fs: mounted savedata0:

p4g: CreateManagedDisplaySeparableLayer
```

The current startup failure is a native memory access error during initialization.

```text
CRASH: signal 11
fault address 0x0
```

The immediate priority is identifying and fixing the missing runtime functionality responsible for this failure.

No successful rendered gameplay frames have been confirmed.

---

## Project Structure

```text
.github/workflows/
    Android and shader build workflows

games/p4g/
    Persona 4 Golden-specific code

games/p4g/import/
    Game preparation and shader processing

games/p4g/java/
    Android launcher

runtime/src/
    Nintendo Switch compatibility runtime

runtime/src/nce/
    Native ARM64 execution implementation
```

Additional components may change as development progresses.

---

## Building the Android APK

The project uses GitHub Actions to build experimental Android packages.

### Android APK

Use the workflow:

[Build P4G Android](../../actions/workflows/build-p4g.yml)

### Native Shader Library

The experimental shader conversion library is built through:

[Build P4G Shader](../../actions/workflows/build-p4g-shader.yml)

The Android build integrates the native runtime, launcher, and shader components.

Check the workflow logs and artifacts for build results.

A successful build does not guarantee successful game initialization.

---

## Reporting Problems

Bug reports and diagnostic logs are welcome.

When reporting an issue, include:

- Device model.
- Android version.
- SoC and GPU.
- RAM capacity.
- Game region and version.
- What happened during startup.
- The exported `p4g-log.txt`.

Please do not upload game dumps, encryption keys, firmware, or copyrighted assets.

Reports involving new devices, different Android versions, or reproducible runtime failures are particularly useful.

---

## Development Goals

The main objectives are:

1. Resolve the remaining native imports and startup crashes.
2. Complete the Nintendo Switch system services required by Persona 4 Golden.
3. Initialize the game's graphics subsystem.
4. Establish a working Vulkan rendering pipeline.
5. Reach the title screen.
6. Implement reliable input and audio support.
7. Reach in-game rendering.
8. Improve compatibility, stability, and performance.
9. Support saving and loading.
10. Work toward a fully playable Android experience.

All goals are subject to technical feasibility and testing.

---

## Em Português

### Persona 4 Golden para Android

Este é um projeto experimental que busca executar a versão de Nintendo Switch de **Persona 4 Golden diretamente em dispositivos Android ARM64**, utilizando execução nativa (NCE).

O objetivo é permitir que o próprio processador do celular execute o código ARM64 do jogo, enquanto um runtime fornece compatibilidade com os serviços do Nintendo Switch.

**O projeto ainda está em desenvolvimento e não é jogável.**

Atualmente, o aplicativo consegue reconhecer o jogo, carregar seus módulos executáveis, preparar determinados dados de shaders e iniciar a execução do código original.

Entretanto, a inicialização ainda apresenta falhas antes de chegar à tela de título.

### Como testar

1. Baixe o APK experimental pelo GitHub Actions.
2. Instale no Android.
3. Selecione sua própria cópia do Persona 4 Golden para Nintendo Switch.
4. Selecione suas próprias chaves necessárias.
5. Execute a preparação do jogo.
6. Utilize a opção de teste de inicialização.
7. Exporte o log caso ocorra alguma falha.

A aplicação ainda não oferece uma experiência de jogo funcional.

**Nenhum jogo, arquivo protegido por direitos autorais ou chave de criptografia é distribuído neste repositório.**

---

## Credits and License

This project is based on work from:

- [Super Mario Bros. Wonder Recomp / SwitchGamesRecomp](https://github.com/BelmanteGu/SuperMarioWonderRecomp) — original compatibility runtime.
- [Vita3K](https://github.com/Vita3K/Vita3K) — GXP shader compiler technology used by the experimental shader conversion component.
- Nintendo Switch native execution and compatibility research from the open-source emulation community.

The project includes modifications intended specifically for Persona 4 Golden on Android.

Original third-party licenses and copyright notices must be preserved.

See [LICENSE](LICENSE) and the applicable third-party notices for licensing information.

---

**Persona 4 Golden for Android is an experimental fan project. Development is ongoing, and playable functionality is not yet available.**
