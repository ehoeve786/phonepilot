# Changelog

## Unreleased

- M1: repository skeleton with build-logic, `core/model`, `core/common`, the app shell in `oss`, `pro` and `play` flavors, the module graph guard and CI.
- M2: local MCP server. `core/audit`, `core/orchestrator` (registry, dispatcher, sessions), `core/tools` with `device.info`, `capability/api` and `capability/device`, `server/mcp` (MCP SDK mapping) and `server/http` (Streamable HTTP on 127.0.0.1:8765 with a local bearer token). The app runs the server in a foreground service and shows the token and the Claude Code command.
