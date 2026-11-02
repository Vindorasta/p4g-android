 
# Persona 4 Golden for Android (Native Port)

An experimental native Android port of **Persona 4 Golden (Nintendo Switch version)** for ARM64 smartphones and tablets.

This project uses a custom Nintendo Switch compatibility runtime, native ARM64 execution (NCE), Android system integrations, and Vulkan-based graphics.

Because Nintendo Switch and modern ARM64 Android devices share the AArch64 instruction set, portions of the game's original executable can run directly on the Android device's CPU. The compatibility runtime provides implementations of the Nintendo Switch services and APIs required by the game.

**No PC is required for game preparation:** the Android application supports selecting a game dump and encryption keys directly on the phone.

> **DEVELOPMENT STATUS — EXPERIMENTAL ALPHA / PARTIALLY PLAYABLE**
>
> Persona 4 Golden now successfully reaches the game and renders both 2D interfaces and 3D environments.
>
> Graphics orientation issues have been corrected, and audio playback is working in the current tested build.
>
> The project is no longer limited to startup initialization or a black screen.
>
> However, this is **not a fully tested or optimized release**. Performance, long-term stability, compatibility across devices, and full-game progression remain under investigation.

> **IMPORTANT — COPYRIGHTED CONTENT**
>
> This repository and its APK builds do not include Persona 4 Golden's game executables, assets, encryption keys, firmware, or other copyrighted game content.
>
> Users must provide their own legally obtained game dump and the necessary keys.
>
> Game files and encryption keys are not provided or linked here.

Persona 4 Golden is © ATLUS / © SEGA.

This is an unofficial fan-made technical project and is not affiliated with, sponsored by, or endorsed by ATLUS, SEGA, or Nintendo.

---

## Current Development Status

| Component | Status |
|---|---|
| Android launcher | Working |
| Game dump selection | Working |
| User-provided encryption keys | Working |
| Game preparation | Working in tested configuration |
| Native ARM64 execution (NCE) | Working |
| Game initialization | Working |
| Title screen and 2D interface rendering | Working |
| 3D environment rendering | Working in tested scenes |
| Graphics orientation | Fixed in current tested configuration |
| Audio output | Working |
| NVN/Maxwell shader translation | Working for tested shaders |
| Vulkan rendering | Working in tested configuration |
| Touch/controller support | Not fully validated |
| Saving and loading | Not fully validated |
| Battles and cutscenes | Not fully validated |
| Performance optimization | Pending |
| Long-term stability | Under investigation |
| Complete game playthrough | Not verified |

**Current milestone:** the game can execute with corrected graphics and working sound on the development test device.

The project should still be considered an experimental Alpha rather than a finished Android release.

---

## Installation

### Experimental Android Builds

1. Obtain an experimental APK build from the project's GitHub Actions artifacts, when available.
2. Install the APK on a compatible ARM64 Android device.
3. Open the application and grant the required file permissions.
4. Select your own Persona 4 Golden Nintendo Switch game dump.
5. Select your own `prod.keys` file.
6. Run the game's preparation process.
7. Launch Persona 4 Golden using the application.

The current build may require additional generated shader files and other experimental resources.

### Tested Game Version

| Property | Value |
|---|---|
| Game | Persona 4 Golden |
| Platform | Nintendo Switch |
| Title ID | `010062B01525C000` |
| Region | USA |
| Tested version | Base game, v0 |
| Build ID | `C433D980688380A642C086072F620928DF7D6320000000000000000000000000` |
| Execution architecture | ARM64 / AArch64 |
| Development status | Experimental Alpha |

Other game revisions, updates, and regions have not yet been fully validated.

---

## Requirements

The following capabilities are relevant to running and testing the project:

- ARM64 Android smartphone or tablet.
- Android operating system with native AArch64 execution support.
- Vulkan-capable GPU.
- Sufficient available RAM for native game execution and graphics resources.
- Storage for the user's own game dump and generated files.
- User-provided game encryption keys.

### Development Test Device

| Device | Android | Result |
|---|---|---|
| Samsung Galaxy A35 5G (SM-A356E) | Android 16 | Native execution, 2D/3D rendering, corrected graphics orientation, and audio playback working |

Minimum RAM requirements and broad hardware compatibility have **not yet been established**.

Performance and stability may vary significantly between devices.

---

## Technical Overview

### Native Execution (NCE)

The application loads the game's original ARM64 executable modules from the user's Nintendo Switch game dump.

The runtime maps the modules into memory, applies relocations, resolves imported symbols, and executes compatible AArch64 instructions natively.

Nintendo Switch-specific operations require compatibility handling, including:

- Supervisor calls (`SVC`).
- Thread-local storage and thread-pointer behavior.
- System timers and counters.
- Kernel services.
- Inter-process communication (`IPC`).
- Application lifecycle management.
- File system access.
- Display and graphics interfaces.

Native execution implementation:

`runtime/src/nce/`

### Nintendo Switch Compatibility Runtime

The project builds upon a compatibility runtime originally developed for other native Nintendo Switch game ports.

The runtime implements or adapts components such as:

- Memory management.
- Kernel services.
- File system and save-data access.
- Application services.
- System time.
- Input handling.
- Display and presentation.
- NVN graphics compatibility.
- Audio output.
- Native execution and crash diagnostics.

Relevant implementation paths include:

- `runtime/src/main.cpp`
- `runtime/src/loader.cpp`
- `runtime/src/memory.cpp`
- `runtime/src/ipc.cpp`
- `runtime/src/hle_fs.cpp`
- `runtime/src/hle_vi.cpp`
- `runtime/src/hle_audio.cpp`
- `runtime/src/nvn/`
- `runtime/src/nce/`

### Game Image Support

The runtime reads the user's game image, loads executable modules, and provides access to game resources.

Relevant files:

- `runtime/src/game_image.cpp`
- `runtime/src/game_image.h`

---

## Graphics and Shader Support

### Vulkan Rendering

Persona 4 Golden uses the Nintendo Switch NVN graphics API.

The runtime translates the required rendering operations to Vulkan on Android.

The current tested build can render:

- 2D menus and interface elements.
- Character and dialogue interfaces.
- 3D environments.
- Scene geometry and associated materials.

The current graphics implementation remains experimental and may require further compatibility fixes in untested scenes.

### Graphics Orientation Fix

Earlier development builds displayed incorrect vertical orientation in rendered output.

The current tested configuration uses a presentation-level Y-axis correction for 2D rendering.

An additional targeted correction was applied to the 3D vertex shader:

`937df4e3272e6853.spv`

The shader's final Y position is inverted to correct the affected 3D rendering orientation.

Because this transformation changes triangle winding, a corresponding Vulkan front-face adjustment is applied for that specific vertex shader in:

`runtime/src/nvn/nvn_pipeline.cpp`

This preserves the expected visible faces of the affected 3D geometry.

The presentation correction is implemented in:

`runtime/src/nvn/nvn_present.cpp`

The tested configuration enables the `NX_FLIP_Y` behavior through game-specific initialization.

**These corrections are specific to the current tested shader and runtime configuration.** They should not be assumed to apply unchanged to all games or shader variants.

### Shader Translation

The project includes experimental tools for translating Nintendo Switch Maxwell/NVN shader programs into SPIR-V suitable for Vulkan.

The shader workflow supports:

- Capturing missing shader data.
- Processing shader code and control data.
- Translating shader programs to SPIR-V.
- Validating generated SPIR-V modules.
- Loading generated shader replacements during gameplay.

A local translation harness is used during development:

`spirv_harness`

Generated SPIR-V modules can be validated with the Khronos SPIR-V validation tools.

### Recent Shader Progress

A collected set of 10 shader programs has been processed and validated.

Two previously missing fragment shaders were additionally converted successfully:

- `10b50679f5212ee5.spv`
- `69af7662ddc06327.spv`

Both passed SPIR-V validation targeting Vulkan 1.2.

**SPIR-V validation confirms structural validity, not complete visual correctness or game compatibility.** In-game testing remains necessary.

An earlier experimental GXP-based shader preparation component also exists in the project. GXP and Nintendo Switch NVN/Maxwell shaders are different formats and must not be treated as interchangeable.

Related source paths:

- `games/p4g/import/`
- `games/p4g/import/p4g_shaders.cpp`
- `runtime/src/nvn/`

### Shader Directory

The runtime uses generated shader replacements from the game's Android data directory, including:

`/storage/emulated/0/Persona 4 Golden/spirv/`

Some shaders may require game-specific corrections beyond automatic translation.

---

## Audio Support

**Audio playback is working in the current tested build.**

The compatibility runtime provides Nintendo Switch audio interfaces and connects audio output to the Android host system.

The implementation includes support for:

- Audio output initialization.
- Audio output parameters.
- Audio buffer handling.
- Playback state and synchronization.
- Channel-count reporting.
- Multichannel-to-stereo conversion where required.
- Diagnostic audio logging.

Primary implementation:

`runtime/src/hle_audio.cpp`

Audio functionality has been confirmed during testing, although compatibility across all scenes, devices, output configurations, and audio backends has not yet been exhaustively validated.

---

## Development Progress

### Confirmed Milestones

- Android application and game selection interface implemented.
- Game dump recognition and preparation functional in the tested setup.
- Native modules `main`, `sdk`, and `subsdk0` successfully loaded.
- Native AArch64 execution initialized.
- Game entry point and runtime initialization reached.
- Game progresses beyond its original startup failures.
- 2D graphics rendered successfully.
- 3D environments rendered successfully.
- Vertical graphics orientation corrected.
- Affected 3D triangle winding corrected.
- Additional missing shaders converted and validated.
- Audio playback confirmed working.

### Remaining Work

Current development priorities include:

1. Improving frame-rate stability and rendering performance.
2. Reducing memory usage and shader compilation overhead.
3. Verifying additional scenes, effects, and shader variants.
4. Investigating device-specific graphics issues.
5. Testing long gameplay sessions for crashes and resource leaks.
6. Verifying battles, cutscenes, saving, and loading.
7. Expanding compatibility with other ARM64 Android devices.
8. Improving input and controller behavior where necessary.
9. Validating full-game progression.

The project has advanced beyond initial startup research, but complete compatibility is not yet established.

---

## Project Structure

| Path | Purpose |
|---|---|
| `.github/workflows/` | Android and shader build workflows |
| `games/p4g/` | Persona 4 Golden-specific implementation |
| `games/p4g/import/` | Game preparation and shader processing |
| `games/p4g/java/` | Android launcher |
| `runtime/src/` | Nintendo Switch compatibility runtime |
| `runtime/src/nce/` | Native ARM64 execution |
| `runtime/src/nvn/` | NVN-to-Vulkan graphics implementation |
| `runtime/src/hle_audio.cpp` | Audio compatibility and output |

Additional files and components may change as development progresses.

---

## Building the Android APK

The project uses GitHub Actions for experimental Android builds.

### Android APK

Build workflow:

[Build P4G Android](../../actions/workflows/build-p4g.yml)

### Native Shader Library

Experimental shader build workflow:

[Build P4G Shader](../../actions/workflows/build-p4g-shader.yml)

The Android build integrates the launcher, native runtime, game-specific compatibility code, and graphics components.

Check the workflow logs and generated artifacts for build results.

A successful APK build does not guarantee full gameplay compatibility.

---

## Debugging and Bug Reports

Bug reports and diagnostic logs are welcome.

When reporting a problem, include:

- Device model.
- Android version.
- SoC and GPU.
- Available RAM.
- Game region and version.
- Description of the issue.
- Steps to reproduce.
- Relevant runtime logs.
- Screenshots, when useful.

The runtime produces diagnostic output such as `nx.log`, and the Android launcher may provide log-export functionality.

When reporting graphics problems, note whether the issue affects 2D interfaces, 3D geometry, textures, or presentation.

When reporting audio problems, include whether audio initialization succeeds and whether sound buffers are being submitted.

**Do not upload copyrighted game dumps, encryption keys, firmware, or proprietary game assets.**

---

## Development Roadmap

The next development phase focuses primarily on performance, stability, and broader game compatibility.

Planned areas of work:

1. Optimize Vulkan rendering and graphics pipeline creation.
2. Improve shader caching and translation reliability.
3. Reduce CPU and memory overhead.
4. Verify gameplay features across more scenes.
5. Test long-term stability.
6. Improve device compatibility.
7. Refine Android input and controller support.
8. Validate saving and loading.
9. Continue work toward complete gameplay compatibility.

All goals remain subject to technical feasibility and testing.

---

## Em Português

### Persona 4 Golden para Android

Este é um port experimental da versão de Nintendo Switch de **Persona 4 Golden**, desenvolvido para dispositivos Android ARM64.

O projeto utiliza execução nativa (NCE), permitindo executar partes do código ARM64 original diretamente no processador do celular.

Um runtime de compatibilidade implementa os serviços do Nintendo Switch necessários para o funcionamento do jogo.

### Estado Atual

**O projeto avançou para uma versão Alpha com execução do jogo, gráficos e áudio funcionando.**

Na configuração testada:

- O jogo inicia e executa.
- Os menus e elementos 2D são renderizados.
- Os cenários e modelos 3D são renderizados.
- A orientação dos gráficos foi corrigida.
- As faces invisíveis causadas pela inversão do shader 3D foram corrigidas.
- O áudio funciona.
- Novos shaders foram convertidos e validados.

Ainda existem limitações importantes.

O desempenho precisa de otimizações, e a estabilidade durante sessões longas ainda não foi completamente verificada.

Também não há validação suficiente para garantir o funcionamento de todas as batalhas, cenas, funcionalidades de save ou da campanha inteira.

**Portanto, o projeto é parcialmente jogável e permanece experimental.**

### Como Testar

1. Obtenha um APK experimental disponibilizado pelo projeto.
2. Instale o aplicativo em um dispositivo Android ARM64 compatível.
3. Selecione sua própria cópia legalmente obtida de Persona 4 Golden para Nintendo Switch.
4. Selecione suas próprias chaves necessárias.
5. Execute a preparação do jogo.
6. Inicie o jogo.
7. Caso encontre problemas, exporte os logs para diagnóstico.

### Aviso Legal

Nenhum jogo, arquivo protegido por direitos autorais, chave de criptografia ou firmware é distribuído neste repositório.

O projeto é independente e não possui afiliação com ATLUS, SEGA ou Nintendo.

---

## Credits and License

This project is based on work from:

- [Super Mario Bros. Wonder Recomp / SwitchGamesRecomp](https://github.com/BelmanteGu/SuperMarioWonderRecomp) — original compatibility runtime.
- [Vita3K](https://github.com/Vita3K/Vita3K) — technology used by an earlier experimental GXP shader conversion component.
- Open-source Nintendo Switch native execution, Vulkan, and compatibility research.

The project includes additional modifications specifically developed for Persona 4 Golden on Android.

Original third-party licenses, attribution requirements, and copyright notices must be preserved.

See [LICENSE](LICENSE) and the applicable third-party notices for licensing information.

---

**Persona 4 Golden for Android is an experimental, partially playable native port. Graphics and audio are working in the tested build, while optimization, stability, and full-game compatibility remain ongoing development goals.**
