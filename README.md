# Stack Doctor

A JetBrains IDE plugin that turns your `docker-compose` files into a **service dependency map**,
lets you **edit services right from the map**, and runs a set of **"doctor" checks** for the Docker
networking mistakes that waste hours.

Works in all JetBrains IDEs (it uses only the platform + YAML APIs): PyCharm, IntelliJ IDEA,
WebStorm, GoLand, RubyMine, Rider, and more.

## Status

**v0.3.0.** Feature-complete and stable in daily use; everything below works and the analysis layer
is covered by automated tests. Install from the JetBrains Marketplace, or build the zip from disk
(see [Building & running](#building--running)).

- **Compatibility:** IntelliJ Platform **2025.3+** (since-build `253`), open-ended upper bound — so
  it installs in the current and every newer JetBrains IDE.
- **Built with:** Kotlin, the IntelliJ Platform Gradle Plugin 2.16, Gradle 9.1, JDK/JBR 21.

## Features

- **Whole-project service map.** Every compose file in the project is drawn at once — each file is a
  root node on the left, its services branch off to the right laid out by dependency layer, with
  curved arrows for `depends_on` (and legacy `links`). Category accents, health/port badges, hover
  highlighting, and a severity tint make problem services stand out. File nodes show a service count
  and an error/warning summary. A **Legend** button explains every symbol, line and colour.
- **Draggable nodes.** Drag any service or file node to rearrange the map; the dependency arrows
  follow. Your layout is kept for the session; **Reset layout** restores the automatic arrangement.
- **Edit services in place.** Click a service node to open an editable form for its parameters —
  image, ports, expose, `depends_on`, volumes, networks, env files, environment, healthcheck and
  restart policy. **Save** writes only the changed fields back into the compose file in one undoable
  step. **Add field** adds a parameter the service doesn't declare yet (a healthcheck, a restart
  policy, expose, …), ready to fill in.
- **Merged preview.** Split your config across a base and an override
  (`docker-compose.override.yml`, a `*.prod.yml`, or any two files)? **Merged preview** lets you pick
  the two files and see the effective configuration Docker would actually run — with the doctor
  checks re-run on the *merged* result (so it surfaces problems that only exist after the merge) and
  the values the overlay contributed highlighted.
- **Generate healthchecks.** One toolbar button adds a context-aware `healthcheck:` to every service
  that others depend on but lacks one (e.g. `pg_isready` for Postgres, `redis-cli ping` for Redis, an
  HTTP probe for web/proxy services). Also offered per-service and as an inline quick-fix.
- **Diagnostics list.** A linked, collapsible list of findings across all files. Selecting one
  highlights the node in the map, and vice-versa. Click the header to collapse it out of the way.
- **Inline inspections.** The same checks run directly in the compose-file editor, highlighting the
  exact line, with quick-fixes (publish a loopback port on all interfaces, declare a missing named
  volume/network, add a generated healthcheck) and navigation.

## Getting started

1. **Install & open a project** that contains at least one Compose file — anything named like
   `docker-compose.yml`, `compose.yaml`, or a `*.yml`/`*.yaml` file with `compose` in its name.
   They're discovered automatically; there's nothing to configure.
2. **Open the tool window:** `View | Tool Windows | Stack Doctor` (it docks on the right). You'll see
   every compose file as a root node, services branching to the right, and `depends_on` arrows
   showing what starts before what. Drag nodes to rearrange.
3. **Read the diagnosis.** Findings are listed below the map and highlighted inline in the YAML
   editor; nodes tint amber or red when a service — or a whole file — has warnings or errors. Collapse
   the **Warnings & errors** list when you want more room for the map.
4. **Edit a service.** Click a service node to open its form, change values (or **Add field** for a
   missing one), and **Save**. Click a file node for its service roster; double-click either to jump
   to it in the editor.
5. **Preview a merge.** Click **Merged preview**, pick a base and an overlay file, and review the
   effective merged configuration and its diagnostics.
6. **Fix it.** Apply the inline quick-fixes on highlighted YAML, or click **Add healthchecks** to
   generate context-aware healthchecks for the services that need one.

Press **Refresh** anytime to re-scan the project and re-run every check.

## Checks

| Check | Severity | Why it matters |
|-------|----------|----------------|
| `depends_on` an unknown service | Error | Compose refuses to start |
| Circular `depends_on` | Error | No valid startup order |
| Host port published by two services | Error | "port is already allocated" |
| Port bound to `127.0.0.1` | Warning | Works locally, unreachable from other devices |
| Service depended on but no healthcheck | Warning | Dependents start before it's ready |
| Named volume not declared | Warning | Compose requires declaration |
| Network used but not declared | Warning | Misconfigured networking |
| Bind-mount source missing on disk | Warning | Docker silently creates an empty dir |
| `env_file` not found | Warning | Variables silently empty |
| Reverse proxy with no published ports | Warning | Proxy unreachable from outside |

## Building & running

Requires a JDK 21 — the JetBrains Runtime (JBR) bundled with any recent JetBrains IDE works. Point
`JAVA_HOME` at it, then use the Gradle wrapper:

```bash
# Point JAVA_HOME at a bundled JBR 21, e.g. on Windows:
#   $env:JAVA_HOME = 'C:\Program Files\JetBrains\PyCharm 2025.3\jbr'

./gradlew buildPlugin     # build/distributions/stack-doctor-<version>.zip
./gradlew runIde          # launch a sandbox IDE with the plugin installed
./gradlew test            # run the unit tests
./gradlew verifyPlugin    # run the JetBrains Plugin Verifier
```

Install the resulting zip via **Settings → Plugins → ⚙ → Install Plugin from Disk…**

## Project layout

- `model/` — IDE-agnostic parsed compose model (`ComposeProject`, `ComposeService`, …).
- `parser/` — YAML-PSI → model parsing, plus the editable-field reader/writer for the service form.
- `diagnostics/` — the pure `StackDoctor` checks and the `ServiceGraph`.
- `analysis/` — project scanning, the combined `ServiceMap`, and the compose-file merge.
- `ui/` — the tool window, the custom-drawn service map, the edit form, and the merge-preview dialog.
- `inspection/` — the editor inspection and its quick-fixes.

## Roadmap

- ~~Inline inspections on the compose file (with quick-fixes and navigation)~~ ✅ shipped
- ~~Whole-project map with context-aware healthcheck generation~~ ✅ shipped
- ~~Edit services from the map, write changes back to the file~~ ✅ shipped
- ~~Merge preview for base + override compose files~~ ✅ shipped
- "Ports exposed vs. actually used in code" by scanning source
- Deeper reverse-proxy checks (upstream hostnames, missing `depends_on`)
- `.env` value resolution per container

## License

[Apache License 2.0](LICENSE). Copyright © 2026 Qrow Solutions.
