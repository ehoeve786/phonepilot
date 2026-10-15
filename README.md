# PocketPilot

PocketPilot turns an Android phone into an MCP server that any AI client can drive: Claude, ChatGPT, Gemini, DeepSeek, Qwen, Ollama or a local model. It needs no root and no PC after setup, and works through Shizuku, Accessibility and MediaProjection.

**Status:** beta in development. Shizuku and Accessibility control, remote access (Tailscale and WireGuard), the MCP server, Agent mode and the automation engine are built. Remaining work is tracked in the technical specification.

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
| `app/` | The Android app: activity, DI graph, flavor wiring, automation host |
| `core/` | Domain and engine: `model` and `common` (pure Kotlin), `orchestrator` (routes MCP calls), `capabilities`, `policy`, `tools`, `automation` (rules engine), `audit`, `imaging`, `designsystem` |
| `capability/` | Phone access: `shizuku`, `accessibility`, `screencapture`, `files`, `notifications`, `apps`, `device`, `api` |
| `server/` | MCP over HTTP (`mcp`, `http`) and OAuth for remote clients (`oauth`) |
| `network/` | Remote access: `tailscale` (embedded, via `go/`), `wireguard`, `public-access`, `certificates`, `api` |
| `agent/` | Agent mode: model `providers-api`, provider adapters (`anthropic`, `gemini`, `openai`, `openai-compatible`), `runtime`, `model-registry` |
| `feature/` | Screens: `agent`, `automations`, `approvals`, `clients`, `network`, `onboarding` |
| `build-logic/` | Gradle convention plugins and the module graph guard (`checkModuleGraph`) |

Each Gradle path mirrors its folder, so `core/policy` is `:core:policy`. Pure Kotlin modules run their tests with `./gradlew :core:policy:test`; Android modules are tested and built in CI.

## License

Apache-2.0 for the core and plugin SDK. Pro modules are closed and live in a separate repository.
