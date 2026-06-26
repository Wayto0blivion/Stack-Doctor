# Stack Doctor

A JetBrains IDE plugin that turns a `docker-compose` file into a **service dependency map** and
runs a set of **"doctor" checks** for the Docker networking mistakes that waste hours.

Works in all JetBrains IDEs (built on the IntelliJ IDEA Community base): PyCharm, IntelliJ IDEA,
WebStorm, GoLand, RubyMine, and more.

## Status

**v0.1.0 — early access, pre-Marketplace.** The plugin is feature-complete for its first milestone
and stable in daily use; everything described below works and is covered by automated tests. It is
not yet published to the JetBrains Marketplace, so for now you install the built zip from disk (see
[Building](#building)).

- **Targets:** IntelliJ Platform 2024.3+ (since-build 243), open-ended upper bound — installs in
  PyCharm 2025.3 and any newer JetBrains IDE.
- **Built with:** Kotlin, the IntelliJ Platform Gradle Plugin 2.x, Gradle 9.1.
- **Shipped:** dependency-graph tool window, 10 YAML doctor checks, inline editor inspections with
  quick-fixes, and the click-to-breakdown service detail popup.
- **Next up:** see the [Roadmap](#roadmap).

## What it does

Open the **Stack Doctor** tool window (right-hand side) and pick a compose file. You get:

- **Service dependency graph** — services laid out by dependency layer, with curved arrows for
  `depends_on` (and legacy `links`), category accents, health/port badges, hover highlighting, and a
  severity tint on problem services. **Click a node** for a line-by-line breakdown of that service's
  config (with plain-English explanations and its findings); **double-click** to jump to it in the editor.
- **Diagnostics** — a linked list of findings. Selecting one highlights the service in the graph
  and vice-versa.
- **Inline inspections** — the same checks run directly in the compose-file editor, highlighting the
  exact line, with quick-fixes (publish a loopback port on all interfaces, declare a missing named
  volume/network) and navigation.

### Checks

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

## Building

Requires a JDK 21 (the JetBrains Runtime bundled with any 2024.3+ IDE works).

```bash
# Windows (PowerShell): point JAVA_HOME at a bundled JBR, then:
./gradlew buildPlugin      # produces build/distributions/stack-doctor-<version>.zip
./gradlew runIde           # launches a sandbox IDE with the plugin installed
./gradlew verifyPlugin     # runs the JetBrains Plugin Verifier
```

Install the resulting zip via **Settings → Plugins → ⚙ → Install Plugin from Disk…**

## Roadmap

- ~~Inline inspections on the compose file itself (with quick-fixes and navigation)~~ ✅ shipped
- "Ports exposed vs. actually used in code" by scanning source
- Deeper reverse-proxy checks (upstream hostnames, missing `depends_on`)
- `.env` value resolution per container

## License

TBD.
