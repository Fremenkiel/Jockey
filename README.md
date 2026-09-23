# Jockey

Jockey is a plugin for JetBrains Rider. It gives AI agents and scripts control over the .NET apps of a solution that is open in Rider. An agent can list the projects of a git repository, read their state, and start, stop, or restart them.

## Purpose

A coding agent that changes a .NET backend must often run the new code. For example, the agent must download a new swagger document to regenerate the API client, or run smoke tests against the new endpoints. If the agent starts the app with `dotnet run` in its own shell, the developer cannot see or debug that process. The agent process can also conflict with the process that the developer started in Rider.

With this plugin, the agent asks Rider to do the work. Rider builds and starts the app with the same run configuration that the developer uses. A run configuration is a named launch setup in the Rider toolbar. The app then shows in the Rider Run tool window, and the developer can watch it, stop it, or attach the debugger.

## Features

- Finds the open Rider solution for a folder, for example a git repository root.
- Lists the run configurations of the solution with state, executor, process ID, and URLs.
- Lists the `.csproj` and `.fsproj` files in the folder with their launch profiles from `Properties/launchSettings.json`.
- Starts, stops, and restarts run configurations, with or without the debugger.
- Waits until a started app accepts connections, and reports a failed startup with the last console lines.
- Records the console output of each run, and reads the real app addresses from the `Now listening on:` lines of ASP.NET Core.

## Interfaces

The plugin offers the same operations in two ways.

- MCP tools. The plugin adds tools to the MCP server that ships with Rider. MCP (Model Context Protocol) is the standard way for AI agents to call tools. Agents such as Claude Code connect to the Rider MCP server.
- HTTP API. The plugin adds routes to the Rider built-in web server at `http://127.0.0.1:63342/api/jockey/`. Scripts and agents without MCP can call these routes with `curl`.

The HTTP API accepts requests from the local machine only. It also rejects requests that carry an `Origin` or `Referer` header, so a web page in a browser cannot use it.

## Usage

The agent skill in [`skills/jockey/SKILL.md`](skills/jockey/SKILL.md) is the usage guide. It describes the tools, the HTTP routes, the result fields, and two workflows: refresh an API client after server changes, and run smoke tests. People can read it too.

To give the skill to Claude Code in all repositories, copy the folder to your user skills:

```sh
cp -R skills/jockey ~/.claude/skills/
```

To give the skill to Claude Code in one repository only, copy the folder to `.claude/skills/` in that repository.

## Requirements

- Rider 2026.2 or later (build 262 or later).
- For MCP access: the MCP server in Rider, enabled in `Settings | Tools | MCP Server`.
- To build: JDK 17 or later to run Gradle. The build downloads JDK 21 to compile the plugin.

## Build and install

1. Set `JAVA_HOME` to a JDK 17 or later, for example:

   ```sh
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
   ```

2. Build the plugin:

   ```sh
   ./gradlew buildPlugin
   ```

3. In Rider, open `Settings | Plugins`, click the gear icon, and select `Install Plugin from Disk`.
4. Select `build/distributions/Jockey-1.0.0.zip` and restart Rider.
5. To connect an agent, open `Settings | Tools | MCP Server`, enable the server, and click `Auto-Configure` for your client.

To try the plugin in a separate Rider sandbox, run `./gradlew runIde`.

If the folder `/Applications/Rider.app` exists, the build compiles against it. Otherwise the build downloads the Rider SDK given by `platformVersion` in `gradle.properties`. To use a different local Rider, edit `platformLocalPath` in `gradle.properties`.

## How it works

The plugin runs inside the Rider frontend, which is the IntelliJ Platform part of Rider. It uses only platform APIs for run configurations. It does not use the ReSharper backend of Rider.

- Starts go through `ProgramRunnerUtil`, so Rider runs its normal before-launch steps, including the build.
- Stops go through the Rider stop action. If a process does not exit within half of the wait time, the plugin kills it.
- A project listener receives every run event, including runs that the developer starts by hand. It keeps the state, the exit code, and the last 5000 console lines of each run configuration.
- The plugin reads the project path of each .NET run configuration from Rider by reflection, because Rider does not publish that class. If this fails, the plugin matches by the name part before `: `.

## Code layout

| Path | Content |
| --- | --- |
| `src/main/kotlin/jockey/core/RunController.kt` | List, start, stop, restart, and readiness checks. |
| `src/main/kotlin/jockey/core/RunStateService.kt` | Run state, exit codes, and console output per run configuration. |
| `src/main/kotlin/jockey/core/SolutionResolver.kt` | Maps a folder to an open solution. |
| `src/main/kotlin/jockey/core/DotnetProjectScanner.kt` | Finds project files and reads `launchSettings.json`. |
| `src/main/kotlin/jockey/core/Models.kt` | Result types for both interfaces. |
| `src/main/kotlin/jockey/http/JockeyRestService.kt` | HTTP API. |
| `src/main/kotlin/jockey/mcp/JockeyToolset.kt` | MCP tools. |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor. |
| `src/main/resources/META-INF/jockey-mcp.xml` | MCP tool registration. If the MCP server plugin is missing, Rider skips this file. |
| `skills/jockey/SKILL.md` | Usage guide and agent skill. |

## Status

Fremenkiel is the author of Jockey. Version 1.0.0 compiles against Rider 2026.2.2. It has no automated tests. It did not get an end-to-end test against a running solution yet.
