# swhurl app template (Kotlin, Micronaut)

A minimal [Micronaut](https://micronaut.io/) service in Kotlin that runs on the [swhurl platform](https://github.com/samclement/swhurl-platform) as it is: OpenTelemetry traces and metrics, JSON logs, a health endpoint and a small image published to GHCR on every push.

## Start a new app

In the platform console, **New app** → stack **kotlin**, or from a checkout of the platform:

```bash
make app-repo NAME=<app> STACK=kotlin ANSWERS="kind=web database=sqlite"
```

Either creates the public repository `samclement/<app>`, waits for its first image and adds it to staging ([start a new app](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#start-a-new-app)). From then on every push to `main` reaches staging on its own; production changes through **Promote to production**.

## How this repository is laid out

A [Copier](https://copier.readthedocs.io/) template, like the [TypeScript one](https://github.com/samclement/swhurl-app-template-typescript): `template/` is the app, and [`copier.yml`](copier.yml) asks for its name, description and features:

| Question | Choices | What it adds |
| --- | --- | --- |
| `kind` | `web` (default), `worker` | `web`: an HTTP service on 8080 with `GET /healthz` (`HttpController.kt`). `worker`: no HTTP server; `Worker.kt` does one unit of work every `WORK_INTERVAL_MS` (default a minute); the platform makes it private |
| `database` | `none` (default), `sqlite` | A SQLite database at `DATABASE_PATH` (`Database.kt`, the `sqlite-jdbc` driver), on a volume the platform keeps and backs up nightly, with migrations in `migrations/NNN_name.sql` applied once each at startup |
| `api` | `none` (default), `openapi` | Web only. Micronaut OpenAPI writes the API description from the controllers at build time; `src/main/resources/openapi.yaml` is that description, committed (`./gradlew openapi`), served at `/openapi.yaml` and named in `swhurl.yaml` (`provides.api`, `provides.apiFile`), so the platform can show other apps what they can call. A test fails while the file and the routes disagree |
| `events` | `none` (default), `asyncapi` | `src/main/resources/asyncapi.yaml` describes the events the app publishes ([AsyncAPI 3](https://www.asyncapi.com/docs)), with an example event (`visited` for web, `worked` for a worker), and `swhurl.yaml` names it (`provides.eventsFile`). `events.publish()` then refuses an event that is not in the file or a payload that does not fit its schema. Every app can publish and subscribe without this answer; it adds the description other apps find |

The Template workflow renders the defaults, each other choice on its own and all of them together (for both kinds), rather than every combination: add a new choice to those lines.

A file named `{% if kind == 'web' %}HttpController.kt{% endif %}` exists only for that answer; `*.jinja` files are rendered. `build.gradle.kts` is not a template, so Renovate can update every version in it: it reads the answers from `gradle.properties`, whose `micronautVersion` Renovate updates through a rule in the preset. The Kotlin standard library and `kotlin-reflect` take the Kotlin plugin's version, so one update moves all of Kotlin. Each app keeps `.copier-answers.yml` for later `copier update`.

Shared by every Kotlin app and kept at the top level: [`.github/workflows/app.yml`](.github/workflows/app.yml), the checks and image build each app's `container.yml` calls, and [`renovate-preset.json`](renovate-preset.json). The **Template** workflow renders every combination of answers and runs `app.yml` on each for every pull request. A new question needs a line in its matrix.

## The contract with the platform

[`swhurl.yaml`](template/swhurl.yaml.jinja) tells the platform what this app is, what its image needs and what it offers, under one comment per kind: its identity (`version`, `stack`, `kind`); its needs (port, health path, user, telemetry, resources and, when used, a database and secret names); its offers (its API and events documents, feature flags). Each field's kind is listed in the platform's [apps guide](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#swhurlyaml). What the platform allows the app (its grants: team, who can reach it, the apps it calls, the events it receives, alerts) is never in it: it is set on the platform.

| | Value | Where |
| --- | --- | --- |
| Port (web) | `8080` | `application.properties` |
| Health path (web) | `GET /healthz` returns `{"ok":true}` once the app has started (with a database, after it opened and migrated) | `HttpController.kt` |
| Start-up | a JVM with the agent starts in about 10 s, longer on a busy node; the platform gives every web app up to 120 s before liveness checks begin, and the smoke test waits as long | the platform |
| Resources | at least 100m CPU, 192Mi memory, limit 384Mi (the heap is 70% of the limit): a need, under `resources` | `swhurl.yaml`, `Dockerfile` |
| User | UID 65532, read-only root filesystem friendly (writes only to `/tmp`, and `/data` with a database) | distroless `nonroot` base image |
| OpenTelemetry | the [Java agent](https://opentelemetry.io/docs/zero-code/java/agent/), loaded by the image; traces and JVM metrics over OTLP, logs not exported (stdout is collected) | `build.gradle.kts` (`copyAgent`), `Dockerfile` |
| Logs | JSON on stdout (logback's `JsonEncoder`), with `trace_id` and `span_id` from the agent | `logback.xml` |
| Image | a `jlink` Java 25 runtime with only the modules the app needs, on `distroless/cc`: about 150 MB with the agent, half a stock JRE image | `Dockerfile` |
| Image tags | `<run number>-<short sha>`, never `latest` | the shared `app.yml` |

Every app also gets `Platform.kt`: it tells a web app whether a request comes from another app (a platform-issued token for this app alone), a signed-in person or nobody, and `platform.call("name", "/path")` calls an app this one has been granted a call to on the platform, with this app's token for it ([calling another app](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#calling-another-app)). It checks tokens with the JDK's own `java.security`; the only added dependency is the OpenTelemetry API, to name the caller on the span.

`Events.kt` does the same for [events](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#events): `events.publish("created", data)` and `events.subscribe("orders.created") { ... }` for the events it publishes (declared in `swhurl.yaml` or `asyncapi.yaml`) and has been granted to receive, over NATS JetStream with the app's own key, as CloudEvents carrying the trace. It adds the `jnats` client.

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

The shared `app.yml` checks each PR and requires those checks for every published image: the image build runs `./gradlew check shadowJar copyAgent` once, then a smoke test that starts the image the way the cluster does (read-only root filesystem, `/tmp` and with a database `/data` writable), read from the app's `swhurl.yaml`: a web app must answer its health path within 120 s (the platform's start-up allowance) and then `/`, and every app must stay up. SQLite images must apply migrations, write events, then preserve those rows and write again after a container restart using the same data directory. Only `main` pushes the image.

First builds import the template's shared registry cache, with separate tags for the web/worker, database and API inputs in `gradle.properties`. Events change source but share dependencies. Missing caches fall back to a build, and older kind/database cache tags remain readable while the new tags warm up. The trimmed Java runtime has its own Docker stage, so app source changes do not rebuild `jlink`. Application compilation and tests still run when their inputs change. After a web app answers (or a worker starts), the smoke test checks that its container stays running every second for four seconds; SQLite repeats that check after restart. This catches an early crash immediately but does not cover failures after that observation window; staging health checks remain necessary.

The generated caller also sets `reuse-pr-image: true` and grants `actions: read`. A successful PR from the app's own repository retains the tested OCI image for one day. If `main` has the same source tree, workflow, helper, resolved base images and builder, it publishes that image under the normal merged-commit tag. Missing or mismatched artifacts take the ordinary build path; fork PRs cannot supply a candidate. Existing callers adopt this through a reviewed Copier update; a workflow-only version update keeps reuse off. The retained export includes maximum build provenance. Its copy helper uses a digest from an immutable upstream Skopeo release, so rolling upstream rebuilds do not remove the pinned image. The default Template matrix combination checks the OCI export against its smoke-tested runtime, as app PRs do. See the platform's [image reuse guide](https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#reusing-a-tested-pr-image) for the matching rules and retained data.

[Renovate](https://docs.renovatebot.com/) opens the update pull requests; each app's `renovate.json` extends this repository's [`renovate-preset.json`](renovate-preset.json): minor, patch and digest updates merge themselves once every check passes, majors wait for you, and Kotlin and Micronaut plugins are grouped. Keep tests for what the app does; an app without them should set `"automerge": false`.

## Template updates

The platform catalogue pins a tested template commit. Releases have immutable version tags, which Copier records in `.copier-answers.yml` even when copying by commit. Renovate's Copier manager proposes complete template updates from these tags, using the saved answers and preserving independent app edits. Copier PRs always require manual review; resolve any conflict markers before merging. The Container workflow runs app checks on the PR and publishes only after a main merge.

For existing apps with a commit hash in their answers file, bootstrap to a released tag with `uvx copier@9.18.2 update --skip-answered --defaults --vcs-ref v0.1.0` on a clean branch and review the diff. Commit it and open a PR; do not edit `_commit` by hand. No template credential or scheduled updater is needed.

A template release can reuse the current Container workflow tag when its checks have not changed. Workflow upgrades still arrive as reviewed version updates. Renovate needs permission to update workflow files when a template release changes that pin; the installed GitHub App already writes workflow updates here.
