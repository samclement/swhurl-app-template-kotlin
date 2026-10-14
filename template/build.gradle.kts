plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.allopen") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.12"
    id("io.micronaut.application") version "5.0.2"
    id("com.gradleup.shadow") version "9.6.1"
}

version = "0.1"
group = "com.swhurl.app"

// The template's answers (gradle.properties). Plain Kotlin here rather than a template file, so
// Renovate can read and update every version below.
val kind = providers.gradleProperty("swhurl.kind").get()
val database = providers.gradleProperty("swhurl.database").get()
val api = providers.gradleProperty("swhurl.api").get()

// Feature flags: the Flags object is generated from swhurl.yaml before every compile (gradle/flags.gradle.kts).
apply(from = "gradle/flags.gradle.kts")
kotlin.sourceSets.main {
    kotlin.srcDir(tasks.named("flags"))
}

repositories {
    mavenCentral()
}

// The OpenTelemetry Java agent, copied next to the jar (copyAgent) and loaded with -javaagent in the
// image: traces and JVM metrics over OTLP, configured by the OTEL_* variables the platform injects.
val otelAgent by configurations.creating

dependencies {
    ksp("io.micronaut.serde:micronaut-serde-processor")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    // kotlin-stdlib comes with the Kotlin plugin, and kotlin("reflect") takes the plugin's version, so both
    // always match the compiler that Renovate updates in the plugins block.
    implementation(kotlin("reflect"))
    // To name the caller on a span and carry the trace through events; the agent supplies the implementation.
    implementation("io.opentelemetry:opentelemetry-api:1.67.0")
    // Events on the platform's NATS (Events.kt).
    implementation("io.nats:jnats:2.26.4")
    // Holds each published event to its schema in asyncapi.yaml, when the app has one (Events.kt).
    // Versions come from the Micronaut platform, like the Jackson they share with it.
    implementation("com.networknt:json-schema-validator")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("tools.jackson.module:jackson-module-kotlin")
    if (kind == "web") {
        ksp("io.micronaut:micronaut-http-validation")
        testImplementation("io.micronaut:micronaut-http-client")
    }
    if (api == "openapi") {
        // Writes the API description from the controllers at build time (META-INF/swagger in the jar).
        ksp("io.micronaut.openapi:micronaut-openapi")
        compileOnly("io.micronaut.openapi:micronaut-openapi-annotations")
    }
    if (database == "sqlite") {
        implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    }
    otelAgent("io.opentelemetry.javaagent:opentelemetry-javaagent:2.32.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.swhurl.app.ApplicationKt"
}

java {
    sourceCompatibility = JavaVersion.toVersion("25")
}

micronaut {
    // A worker has no HTTP server.
    runtime(if (kind == "web") "netty" else "none")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.swhurl.app.*")
    }
}

if (api == "openapi") {
    // `./gradlew openapi`: copies the description generated from the controllers over the committed
    // src/main/resources/openapi.yaml, which the platform reads and the app serves. OpenApiTest fails
    // while the two disagree, so run this after changing a route and commit the result.
    tasks.register("openapi") {
        dependsOn("kspKotlin")
        val generated = layout.buildDirectory.dir("generated/ksp/main/resources/META-INF/swagger")
        val committed = layout.projectDirectory.file("src/main/resources/openapi.yaml")
        doLast {
            generated.get().asFile.listFiles { file -> file.extension == "yml" }!!.single().copyTo(committed.asFile, overwrite = true)
        }
    }
}

val copyAgent by tasks.registering(Copy::class) {
    from(otelAgent)
    into(layout.buildDirectory.dir("agent"))
    rename { "opentelemetry-javaagent.jar" }
}

if (database == "sqlite") {
    // Docker build environments can mount /tmp as noexec, which prevents sqlite-jdbc from loading
    // its extracted native library. Keep the test-only extraction directory inside the build tree.
    tasks.withType<Test>().configureEach {
        val sqliteTmp = layout.buildDirectory.dir("sqlite-tmp").get().asFile
        systemProperty("org.sqlite.tmpdir", sqliteTmp.absolutePath)
        doFirst { sqliteTmp.mkdirs() }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
