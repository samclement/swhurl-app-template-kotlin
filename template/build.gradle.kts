plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.allopen") version "2.4.20"
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
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("tools.jackson.module:jackson-module-kotlin")
    if (kind == "web") {
        ksp("io.micronaut:micronaut-http-validation")
        testImplementation("io.micronaut:micronaut-http-client")
    }
    if (database == "sqlite") {
        implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    }
    otelAgent("io.opentelemetry.javaagent:opentelemetry-javaagent:2.31.1")
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
