# PocketPilot repository notes

- Product spec: "PocketPilot: Repository and Technical Specification" (Claude Doc, https://claude.ai/code/artifact/8ae2c0d4-11bd-4f6c-96b3-166dc74130ae). Its section 2 module rules are enforced by `./gradlew checkModuleGraph`; update `ModuleGraphRules` and its test when adding a rule.
- Package root `app.pocketpilot`; Gradle paths mirror folders (`:core:policy`). New modules apply a `pocketpilot.*` convention plugin from `build-logic`, never AGP or Kotlin plugins directly.
- `core/model` and `core/common` are pure Kotlin: no Android, no other project dependencies.
- Only `app` has product flavors (`oss`, `pro`, `play`). Flavor differences go through `PolicyProfile`, bound per flavor in `app/src/<flavor>/`.
- Versions live only in `gradle/libs.versions.toml`. AGP, Kotlin and KSP are bumped together.
- Style is ktlint (`ktlint_official`, see `.editorconfig`); run `./gradlew ktlintFormat` before committing.
- Cloud sessions cannot reach dl.google.com, so Android modules only build in CI. Pure Kotlin modules can be compiled locally.
