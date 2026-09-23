---
name: jockey
description: >
  List, start, stop, and restart the .NET projects of a solution that is open in JetBrains Rider,
  through the Jockey plugin. Use it to run a .NET app from a git repository and to read
  its state, URLs, or console output. Use it to restart the app after code changes. Use it before you
  download a swagger or OpenAPI document to regenerate a client, or before you run smoke tests.
  Triggers: "start the API", "restart the backend", "is the service running", "regenerate the API
  client", "update swagger", "run smoke tests", "Rider run configuration".
---

# Jockey

The Jockey plugin lets you control .NET apps that are open in Rider. The plugin starts each app through a Rider run configuration. A run configuration is a named launch setup in Rider. Each start builds the project first and shows the app in the Rider Run tool window, so the developer sees what you do.

You can use two interfaces. Both have the same operations and the same results.

- MCP tools: `dotnet_list_projects`, `dotnet_start_project`, `dotnet_stop_project`, `dotnet_restart_project`, `dotnet_project_logs`, `rider_list_solutions`.
- HTTP API: `http://127.0.0.1:63342/api/jockey/`, for use with `curl`.

If the MCP tools are in your tool list, use them. If they are not, use the HTTP API.

## Preconditions

1. Make sure that Rider runs and that it has the solution of the repository open. If it does not, tell the user. You cannot open Rider or a solution yourself.
2. Get the absolute repository path:

   ```sh
   git rev-parse --show-toplevel
   ```

3. Pass this path as `repoPath` to each MCP tool, or as `repo` to each HTTP route.

If the HTTP API does not answer on port 63342, try ports 63343 to 63350. If port 63342 is in use, Rider takes the next free port. Make sure that `GET /api/jockey/` returns the list of routes.

## Find the run configuration

Call `dotnet_list_projects` first:

```sh
curl -s "http://127.0.0.1:63342/api/jockey/projects?repo=$REPO"
```

The result has two lists.

- `runConfigurations`: the things you can start. Use the `name` field of an entry in all other calls. Rider names .NET launch profiles `<Project>: <Profile>`, for example `Api: https`.
- `projects`: the `.csproj` and `.fsproj` files under the repository, with their launch profiles and the names of their run configurations. A project with no run configuration is a library or a test project. You cannot start it with this plugin.

If a project has exactly one run configuration, you can also pass the project name as `name`.

Each run configuration entry has these fields:

| Field | Meaning |
| --- | --- |
| `state` | `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, or `FAILED`. `STARTING` includes the build. |
| `executor` | `run` or `debug`. |
| `pid` | Process ID. Some run types do not expose it, and the value is then `null`. |
| `listeningUrls` | Addresses from the `Now listening on:` lines that ASP.NET Core writes at startup. These are the real addresses. |
| `applicationUrls`, `launchUrl` | Values from `Properties/launchSettings.json`. If `listeningUrls` is empty, use these values. |
| `lastExitCode`, `lastError` | Result of the last run. |

## Start, restart, and stop

| Goal | MCP tool | HTTP route |
| --- | --- | --- |
| Start a stopped app | `dotnet_start_project` | `POST /start?repo=R&name=N&wait=120` |
| Apply code changes to a running app | `dotnet_restart_project` | `POST /restart?repo=R&name=N&wait=120` |
| Stop an app | `dotnet_stop_project` | `POST /stop?repo=R&name=N&wait=30` |
| Read console output | `dotnet_project_logs` | `GET /logs?repo=R&name=N&tail=200` |

Start and restart build the project, start it, and wait until the app is ready or the wait time ends. If the user asks for the debugger, set `debug=true`. Otherwise leave it out. If you must not block, set the wait time to `0` and poll `dotnet_list_projects`.

A start on an app that runs already returns `already_running` and does not rebuild. After you change code, use restart. A start does not apply your changes.

Read the `outcome` field of the result:

| Outcome | Meaning | What you do |
| --- | --- | --- |
| `started`, `restarted` | The app is ready. `ready` is `true`. | Continue. |
| `already_running` | The app ran before the call. | If you changed code, restart. |
| `stopped` | The app stopped. | Continue. |
| `failed` | The build failed, or the app exited during startup. | Read `message` and `logTail`. Fix the cause and restart. |
| `timeout` | The wait time ended first. | Read `message`. A large build can take longer, so poll `dotnet_list_projects` or increase the wait time. |

If `logTail` is empty after a failed start, the build failed before the app started. Build errors do not show in the console output. Run `dotnet build` in the project folder to read them.

## Workflow: refresh the API client after server changes

Use this workflow after you change controllers, endpoints, or data contracts in the server.

1. Call `dotnet_list_projects`. Find the run configuration of the API project. Write down its current `state`.
2. Call `dotnet_restart_project` for that run configuration. If the app was stopped, call `dotnet_start_project`.
3. Make sure that the outcome is `restarted` or `started` and that `ready` is `true`. If not, read the section above and fix the problem first.
4. Take the base URL from `listeningUrls`. Prefer the `http://` entry, because local tools then need no certificate.
5. Download the OpenAPI document. Try the path that the project uses:

   ```sh
   # Swashbuckle (AddSwaggerGen)
   curl -sf "$BASE_URL/swagger/v1/swagger.json" -o openapi.json
   # Microsoft.AspNetCore.OpenApi (AddOpenApi), .NET 9 and later
   curl -sf "$BASE_URL/openapi/v1.json" -o openapi.json
   ```

   If both paths fail, search the server code for `MapOpenApi`, `UseSwagger`, or `RouteTemplate` to find the path. The document is often available in the `Development` environment only.

6. Save the document where the client project expects it. Search the client for the old file or for the generator configuration, for example `nswag.json`, `kiota-lock.json`, or an `openapi-typescript` script in `package.json`.
7. Run the client generator that the repository uses. Do not introduce a different generator.
8. Build the client and fix the compile errors that the new contracts cause.

## Workflow: run smoke tests

1. Make sure that the app runs the current code. If you changed code since the last start, restart it.
2. Take the base URL from `listeningUrls`.
3. Run the smoke tests of the repository against that URL. Look for a script or a test project first, for example a `smoke` folder, a `*.SmokeTests` project, or a `test:smoke` script.
4. If the repository has no smoke tests, send requests to a health endpoint and to two or three main endpoints with `curl`. Report the status codes.
5. If a test fails with a 5xx status, call `dotnet_project_logs` and read the exception in the output.

## Clean up

If you started an app yourself, stop it when your task is done. If the app ran before you began, leave it running. The developer can use it.

## Limits

- The plugin works only while Rider runs with the solution open.
- The console output exists only for runs that started after Rider loaded the plugin.
- If `applicationUrls` and `listeningUrls` are both empty for a web app, the plugin cannot link the run configuration to its project. Read the port from the `logs` output instead.
- A start never opens a Rider dialog. If a run configuration is invalid, the start fails or times out. Tell the user to fix the run configuration in Rider.
