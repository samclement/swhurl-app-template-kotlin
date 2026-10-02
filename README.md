# swhurl app template (Kotlin, Micronaut)

A minimal [Micronaut](https://micronaut.io/) service in Kotlin that runs on the [swhurl platform](https://github.com/samclement/swhurl-platform) as it is: OpenTelemetry traces and metrics, JSON logs, a health endpoint and a small image published to GHCR on every push.

## Start a new app

In the platform console, **New app** → stack **kotlin**, or from a checkout of the platform:

```bash
make app-repo NAME=<app> STACK=kotlin ANSWERS="kind=web database=sqlite"
```

Either creates the public repository `samclement/<app>`, waits for its first image and adds it to staging ([apps guide](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#start-from-the-template)). From then on every push to `main` reaches staging on its own; production changes through **Promote to prod**.

## How this repository is laid out

A [Copier](https://copier.readthedocs.io/) template, like the [TypeScript one](https://github.com/samclement/swhurl-app-template-typescript): `template/` is the app, and [`copier.yml`](copier.yml) asks for its name, description and features:

| Question | Choices | What it adds |
| --- | --- | --- |
| `kind` | `web` (default), `worker` | `web`: an HTTP service on 8080 with `GET /healthz` (`HttpController.kt`). `worker`: no HTTP server; `Worker.kt` does one unit of work every `WORK_INTERVAL_MS` (default a minute); the platform makes it private |
| `database` | `none` (default), `sqlite` | A SQLite database at `DATABASE_PATH` (`Database.kt`, the `sqlite-jdbc` driver), on a volume the platform keeps and backs up nightly, with migrations in `migrations/NNN_name.sql` applied once each at startup |

A file named `{% if kind == 'web' %}HttpController.kt{% endif %}` exists only for that answer; `*.jinja` files are rendered. `build.gradle.kts` is not a template, so Renovate can update every version in it: it reads the answers from `gradle.properties`. Each app keeps `.copier-answers.yml` for later `copier update`.

Shared by every Kotlin app and kept at the top level: [`.github/workflows/app.yml`](.github/workflows/app.yml), the checks and image build each app's `container.yml` calls, and [`renovate-preset.json`](renovate-preset.json). The **Template** workflow renders every combination of answers and runs `app.yml` on each for every pull request. A new question needs a line in its matrix.

## The contract with the platform

| | Value | Where |
| --- | --- | --- |
| Port (web) | `8080` | `application.properties` |
| Health path (web) | `GET /healthz` returns `{"ok":true}` (with a database, only once it answers) | `HttpController.kt` |
| Start-up | up to 120 s before liveness checks begin (`startupSeconds` in `swhurl.yaml`): a JVM with the agent starts in about 10 s, longer on a busy node | `swhurl.yaml` |
| Resources | 100m CPU, 192Mi memory, limit 384Mi (the heap is 70% of the limit) | `swhurl.yaml`, `Dockerfile` |
| User | UID 65532, read-only root filesystem friendly (writes only to `/tmp`, and `/data` with a database) | distroless `nonroot` base image |
| OpenTelemetry | the [Java agent](https://opentelemetry.io/docs/zero-code/java/agent/), loaded by the image; traces and JVM metrics over OTLP, logs not exported (stdout is collected) | `build.gradle.kts` (`copyAgent`), `Dockerfile` |
| Logs | JSON on stdout (logback's `JsonEncoder`), with `trace_id` and `span_id` from the agent | `logback.xml` |
| Image | a `jlink` Java 25 runtime with only the modules the app needs, on `distroless/cc`: about 150 MB with the agent, half a stock JRE image | `Dockerfile` |
| Image tags | `<run number>-<short sha>`, never `latest` | the shared `app.yml` |

What the platform injects: `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_PROTOCOL`, `OTEL_SERVICE_NAME` (the app name), and `DATABASE_PATH` with a database. Sign-in happens before requests reach the app; it can read the user from `X-Auth-Request-Email`.

The JVM runs with `-XX:TieredStopAtLevel=1` (only the fast JIT tier: start-up in a fraction of the time, a little less peak speed) and the serial GC. Remove the flag in the `Dockerfile` for a CPU-heavy app.

## Local development

Render an app first, then in it (needs a JDK 25; Gradle downloads everything else):

```bash
uvx copier copy --vcs-ref HEAD --data app_name=try-it . /tmp/try-it && cd /tmp/try-it
./gradlew run                           # the agent stays off locally (the image loads it)
curl http://localhost:8080/healthz      # web
./gradlew check                         # compile and tests, as CI does
```

## Checks and dependency updates

Every pull request and every push to `main` of an app runs the same checks from the shared `app.yml`: `./gradlew check` (compile and tests), an image build, and a smoke test that starts the image the way the cluster does (read-only root filesystem, `/tmp` and with a database `/data` writable), read from the app's `swhurl.yaml`: a web app must answer its health path and `/`, and every app must stay up. Only `main` pushes the image.

[Renovate](https://docs.renovatebot.com/) opens the update pull requests; each app's `renovate.json` extends this repository's [`renovate-preset.json`](renovate-preset.json): minor, patch and digest updates merge themselves once every check passes, majors wait for you, and Kotlin and Micronaut plugins are grouped. Keep tests for what the app does; an app without them should set `"automerge": false`.
