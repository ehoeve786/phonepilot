# PocketPilot

PocketPilot turns an Android phone into an MCP server that any AI client can drive: Claude, ChatGPT, Gemini, DeepSeek, Qwen, Ollama or a local model. It needs no root and no PC after setup, and works through Shizuku, Accessibility and MediaProjection.

**Status:** early development (milestone M1, repository skeleton). Nothing here is usable on a phone yet.

## Build

Requirements: JDK 17 or newer and the Android SDK (API 36).

```sh
./gradlew :app:assembleOssDebug   # open-source build
./gradlew :app:assembleDebug      # all three flavors: oss, pro, play
./gradlew checkModuleGraph        # module dependency rules
./gradlew ktlintCheck test        # style and unit tests
```

## Flavors

| Flavor | Contents | Application ID |
|---|---|---|
| `oss` | All open modules | `app.pocketpilot.oss` |
| `pro` | oss plus closed Pro modules from `pro/` (a private submodule, optional) | `app.pocketpilot.pro` |
| `play` | pro minus anything Play policy forbids, plus Play Billing | `app.pocketpilot` |

Debug builds add `.debug`, so they install next to release builds.

## Layout

| Path | What lives there |
|---|---|
| `app/` | The Android app: activity, DI graph, flavor wiring |
| `core/model/` | Domain types shared everywhere (pure Kotlin) |
| `core/common/` | Small utilities such as ULIDs and clocks (pure Kotlin) |
| `build-logic/` | Gradle convention plugins and the module graph guard |

Further modules (orchestrator, capabilities, MCP server, network providers, agent, features) arrive milestone by milestone.

## License

Apache-2.0 for the core and plugin SDK. Pro modules are closed and live in a separate repository.
