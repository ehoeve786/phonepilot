# Changelog

## Unreleased

- M1: repository skeleton with build-logic, `core/model`, `core/common`, the app shell in `oss`, `pro` and `play` flavors, the module graph guard and CI.
- M2: local MCP server. `core/audit`, `core/orchestrator` (registry, dispatcher, sessions), `core/tools` with `device.info`, `capability/api` and `capability/device`, `server/mcp` (MCP SDK mapping) and `server/http` (Streamable HTTP on 127.0.0.1:8765 with a local bearer token). The app runs the server in a foreground service and shows the token and the Claude Code command.
- M3: eyes and hands. `capability/accessibility` (Accessibility service for snapshots, node actions, gestures and screenshots), `capability/apps` (launcher apps, launch, links), `capability/screencapture` (WebP/JPEG/PNG encoder) and `core/imaging` (crop, redact, scale, dHash). New tools: `screen.snapshot`, `screen.find`, `screen.wait_for`, `screen.wait_for_change`, `screen.capture`, `ui.tap`, `ui.long_press`, `ui.swipe`, `ui.type_text`, `ui.press_key`, `ui.global_action`, `app.list`, `app.current`, `app.launch`, `app.open_url`. Tool errors now repeat the error code and recovery hint in the text. `app.stop` and `app.send_intent` wait for Shizuku (M4) and the policy engine (M5).
