# nf-metro (Nextflow plugin)

A Nextflow plugin that drives [nf-metro](https://github.com/pinin4fjords/nf-metro)'s
live-progress server from a running pipeline. It registers a `TraceObserverV2`
that translates Nextflow task lifecycle events into the weblog-compatible JSON
the nf-metro server ingests, lighting up stations on a metro map in real time.

No `-with-weblog` flag is needed: the plugin emits the events itself.

## How it works

nf-metro (the Python tool) can serve a live page:

```bash
nf-metro serve map.mmd --port 8090 [--token T]
```

That server renders the map once and then lights up its stations from Nextflow
weblog events POSTed to `/events`. It exposes:

- `GET /`       the live page
- `GET /state`  JSON `{"run":{...}, "stations":{<id>:{"state","done","total"}}}`
- `POST /events` weblog event ingestion

This plugin POSTs events in the stock Nextflow `-with-weblog` shape, so the
Python server runs unchanged.

## Why use the plugin?

You don't have to: nf-metro works with plain Nextflow `-with-weblog`, and the
shared dashboard can be driven by a `curl` to `/maps` plus a per-run
`-with-weblog` URL. The plugin emits the same events - it's a convenience layer
that moves the wiring into config and handles the server lifecycle for you.

| Task | Without the plugin (`-with-weblog`) | With the plugin |
|------|--------------------------------------|-----------------|
| Wiring | `-with-weblog <url>` on every run | One `plugins { id 'nf-metro' }` + a `metro {}` block in `nextflow.config` |
| Run the server | Start `nf-metro serve` yourself in another shell | **Managed mode** spawns and stops it for the run (and can open the browser) |
| Shared dashboard | `curl` the map to `/maps`, read the run id, then point `-with-weblog` at `/r/<id>/events` | **Central mode** registers the map and wires the per-run endpoint automatically |
| Find the map | construct the URL yourself | prints the live URL in the run log |

It's worth it when you want the integration to live in the pipeline's config,
the server started and stopped for you, or runs to self-register on a shared
dashboard (the register-then-emit step is awkward by hand).

## Three modes

Mode is selected by config (the `metro` scope). One observer, three behaviours:

| Mode    | Trigger                              | Behaviour                                                        |
|---------|--------------------------------------|------------------------------------------------------------------|
| central | `metro.server` + `metro.map` set     | Register the map on a persistent `nf-metro serve-multi` server and stream to the run's endpoint. |
| attach  | `metro.url` set (and no `server`)    | POST events to an existing single-map server's `/events` URL.    |
| managed | `metro.map` set (no `server`/`url`)  | Spawn `nf-metro serve <map>` itself, POST to it, stop it at end.  |
| no-op   | none set                             | Log a warning and do nothing. The run is unaffected.             |

Precedence when more than one is set: central > attach > managed.

When to use which:

- **managed** - a one-off local run: nothing to start or stop, the plugin
  spawns a single-map server for the run and tears it down at the end.
- **attach** - a single-map server you keep running yourself while iterating
  (one stable URL, reset on each re-run), or any server already up.
- **central** - many pipelines or a history of runs on one shared dashboard
  (a lab or CI box running `nf-metro serve-multi`); each run is its own entry.

### Config options (`metro` scope)

| Option         | Mode      | Default        | Meaning                                                        |
|----------------|-----------|----------------|----------------------------------------------------------------|
| `metro.server` | central   | -              | Base URL of a persistent `serve-multi` server to register on.  |
| `metro.url`    | attach    | -              | `/events` URL of an already-running single-map server.         |
| `metro.map`    | central/managed | -        | Path to the `.mmd` map (registered on the server, or served).  |
| `metro.port`   | managed   | a free port    | Port for the spawned server.                                   |
| `metro.token`  | all       | auto (managed) | Auth token. In managed mode one is auto-generated if unset.    |
| `metro.open`   | managed   | `false`        | Open the live map in the default browser at start.             |
| `metro.binary` | managed   | `nf-metro`     | Path/name of the nf-metro executable to spawn.                 |

Example configs:

```groovy
// central: report into a shared, long-lived dashboard (nf-metro serve-multi)
plugins { id 'nf-metro@0.1.0' }
metro {
    server = 'http://metro.lab.internal:8080'
    map    = '/path/to/pipeline.mmd'
}
```

```groovy
// attach to an existing single-map server
plugins { id 'nf-metro@0.1.0' }
metro { url = 'http://localhost:8090/events' }
```

```groovy
// managed: plugin runs the server for the lifetime of the pipeline
plugins { id 'nf-metro@0.1.0' }
metro {
    map  = '/path/to/pipeline.mmd'
    port = 8091      // optional; a free port is chosen if omitted
    open = true      // optional; open the browser
}
```

## TaskEvent -> weblog mapping

The V2 callbacks each carry a `TaskEvent`, from which the plugin reads the
event's `TraceRecord` (`event.getTrace()`) and pulls:

- `process`  via `TraceRecord.getProcessName()` (fully-qualified, e.g.
  `NFCORE_RNASEQ:RNASEQ:TRIMGALORE`), falling back to the `process` store key.
- `task_id`  via `TraceRecord.getTaskId()`, falling back to the `task_id` key,
  parsed to an int (`-1` if absent/non-numeric).
- `status`   the lifecycle status, taken from the callback (or the trace's
  `status` key for the completed/failed distinction).

The emitted payloads:

| V2 callback        | weblog `event`       | `trace.status`           |
|--------------------|----------------------|--------------------------|
| `onFlowBegin`      | `started` (+runName) | -                        |
| `onTaskSubmit`     | `process_submitted`  | `SUBMITTED`              |
| `onTaskStart`      | `process_started`    | `RUNNING`                |
| `onTaskComplete`   | `process_completed`  | `COMPLETED` or `FAILED`  |
| `onTaskCached`     | `process_completed`  | `COMPLETED`              |
| `onFlowComplete`   | `completed`          | -                        |
| `onFlowError`      | `error`              | -                        |

Example payload:

```json
{"event":"process_started",
 "trace":{"task_id":7,"process":"NFCORE_RNASEQ:RNASEQ:TRIMGALORE","status":"RUNNING"}}
```

The server matches `trace.process` case-insensitively against the map's
`%%metro process:` directives, so a bare name matches a scoped one.

## Fail-soft guarantee

Every observer callback swallows its own errors. A down server, an unreachable
URL, a missing `nf-metro` binary, or a failed spawn never propagates out of a
callback and never fails the pipeline. Events are dispatched on a single daemon
thread so the pipeline's own threads never block on the network.

## JVM <-> Python caveat (managed mode)

Managed mode shells out to the `nf-metro` Python CLI via `ProcessBuilder`, so
the executable must be resolvable from the JVM's environment:

- Put `nf-metro` on `PATH` before launching Nextflow, e.g.
  `export PATH=/path/to/nf-metro-env/bin:$PATH`, **or**
- Set `metro.binary` to an absolute path, e.g.
  `metro.binary = '/path/to/nf-metro-env/bin/nf-metro'`.

If the binary can't be found or the server doesn't come up within ~15s, the
plugin logs a clear warning and disables the live map (degrading to attach mode
if a `url` was also given, else to a no-op). The pipeline continues regardless.

## Build / install / test

```bash
# build the plugin zip
make assemble          # or: ./gradlew assemble

# run unit tests
make test              # or: ./gradlew test

# install into ~/.nextflow/plugins
make install           # or: ./gradlew install
```

Alternatively, run Nextflow against the build tree without installing:

```bash
NXF_PLUGINS_DEV=/path/to/nf-metro-plugin nextflow run ... \
    -plugins nf-metro@0.1.0
```

### End-to-end validation

The `validation/` directory is self-contained: a toy workflow that only
`sleep`s (~50s, 8 processes, no containers), a bundled `validation/pipeline.mmd`
map, and a ready `nextflow.config` per mode (no env vars - the config carries
everything; the map is found via `${projectDir}/../pipeline.mmd`).

Central mode (shared dashboard - the recommended demo):

```bash
# shell 1: one persistent server, hosts every run that reports in
nf-metro serve-multi --port 8080
# open the dashboard at http://localhost:8080/

# shell 2 (and 3, 4...): each run registers its map and reports in
cd validation/central && nextflow run main.nf
# prints "registered on ...; live map: http://localhost:8080/r/<id>/"
```

Attach mode:

```bash
# shell 1 (nf-metro Python env): start a single-map server
nf-metro serve validation/pipeline.mmd --port 8090   # open http://localhost:8090/

# shell 2 (Nextflow env, plugin installed):
cd validation/attach && nextflow run main.nf
```

Managed mode (plugin runs the server itself):

```bash
# nf-metro must be on PATH (or set metro.binary in the config)
cd validation/managed && nextflow run main.nf
# the server is spawned on a free port, lights up, and is torn down at the end
```

## Requirements

- Nextflow 25.10.0+ (uses the V2 trace observer API
  `nextflow.trace.TraceObserverV2` and the `nextflow.config.spec` config scope).
- Java 17 or later to build and run (verified on 17 and 21; the
  `io.nextflow.nextflow-plugin` Gradle plugin handles the toolchain).
- For managed mode: the `nf-metro` Python tool installed and reachable
  (on `PATH`, or via an absolute `metro.binary`).
