// Generates the Flags object from the feature flags declared in swhurl.yaml: one typed property per
// flag, written under build/generated (build.gradle.kts compiles it with the app). It runs before
// every compile, so swhurl.yaml is the only place a flag is written, and refuses what the platform
// refuses: https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#feature-flags
import org.yaml.snakeyaml.Yaml

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.yaml:snakeyaml:2.7")
    }
}

val environments = listOf("staging", "prod")
val types = mapOf("boolean" to "Boolean", "integer" to "Int", "number" to "Double", "string" to "String")
val keywords = setOf("as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
    "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while")

fun isInteger(value: Any?) = value is Int || value is Long || value is java.math.BigInteger
fun isNumber(value: Any?) = value is Number && (value !is Double || value.isFinite())

/** Why one declared flag cannot be used, or "". */
fun problem(name: String, flag: Any?): String {
    if (name.length > 40 || !Regex("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*").matches(name)) {
        return "flag name '$name' must be kebab case, each part starting with a letter (new-checkout), at most 40 characters"
    }
    if (flag !is Map<*, *>) return "flags.$name must be a mapping with type and default"
    val type = flag["type"]
    if (type !in types) return "flags.$name.type must be one of ${types.keys.joinToString(", ")}"
    val numeric = type == "integer" || type == "number"
    val allowed = listOf("type", "description", "default") + environments +
        (if (numeric) listOf("min", "max") else emptyList()) + (if (type == "string") listOf("enum") else emptyList())
    val unknown = flag.keys.map { it.toString() }.filter { it !in allowed }.sorted()
    if (unknown.isNotEmpty()) return "flags.$name may not set ${unknown.joinToString(", ")} (a $type flag sets ${allowed.sorted().joinToString(", ")})"
    if ("description" in flag && flag["description"] !is String) return "flags.$name.description must be text"
    if ("default" !in flag) return "flags.$name.default is required"
    fun fits(value: Any?) = when (type) {
        "boolean" -> value is Boolean
        "integer" -> isInteger(value) && (value as Number).toLong() in Int.MIN_VALUE..Int.MAX_VALUE
        "number" -> isNumber(value)
        else -> value is String
    }
    for (bound in listOf("min", "max")) {
        if (bound in flag && !(if (type == "integer") isInteger(flag[bound]) else isNumber(flag[bound]))) {
            return "flags.$name.$bound must be a${if (type == "integer") "n integer" else " number"}"
        }
    }
    val min = (flag["min"] as Number?)?.toDouble()
    val max = (flag["max"] as Number?)?.toDouble()
    if (min != null && max != null && min > max) return "flags.$name.min is above its max"
    val choices = flag["enum"]
    if (choices != null && (choices !is List<*> || choices.isEmpty() || !choices.all { it is String } || choices.toSet().size != choices.size)) {
        return "flags.$name.enum must list distinct strings"
    }
    for (key in listOf("default") + environments) {
        if (key !in flag) continue
        val value = flag[key]
        if (!fits(value)) return "flags.$name.$key must be a${if (type == "integer") "n" else ""} $type, not $value"
        val number = (value as? Number)?.toDouble()
        if (numeric && number != null && (number < (min ?: number) || number > (max ?: number))) return "flags.$name.$key is outside min and max"
        if (choices is List<*> && value !in choices) return "flags.$name.$key must be one of ${choices.joinToString(", ")}"
    }
    return ""
}

fun literal(type: String, value: Any?): String = when {
    value == null -> "null"
    type == "number" -> (value as Number).toDouble().toString()
    type == "string" -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n") + "\""
    else -> value.toString()
}

fun property(name: String): String {
    val camel = name.replace(Regex("-([a-z])")) { it.groupValues[1].uppercase() }
    return if (camel in keywords) "`$camel`" else camel
}

/** The source of Flags.kt for the flags of a parsed swhurl.yaml; fails naming the first problem. */
fun generate(declaration: Any?): String {
    val flags = (declaration as? Map<*, *>)?.get("flags") ?: emptyMap<String, Any>()
    if (flags !is Map<*, *>) throw GradleException("swhurl.yaml: flags must be a mapping")
    for ((name, flag) in flags) {
        val found = problem(name.toString(), flag)
        if (found.isNotEmpty()) throw GradleException("swhurl.yaml: $found")
    }
    val lines = mutableListOf(
        "// Generated from the flags in swhurl.yaml by ./gradlew flags: edit swhurl.yaml, not this file.",
        "// https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#feature-flags",
        "package com.swhurl.app",
        "",
        "/** This app's feature flags: an override from the console, else the value swhurl.yaml declares for this environment. */",
        "object Flags {",
    )
    lines += if (flags.isEmpty()) "    val store = FlagStore(emptyMap())" else "    val store = FlagStore(mapOf("
    for ((name, flag) in flags) {
        flag as Map<*, *>
        val type = flag["type"] as String
        val values = (listOf("default") + environments).joinToString(", ") { "$it = ${literal(type, flag[it])}" }
        val bounds = listOf("min", "max").filter { it in flag }.joinToString("") { ", $it = ${(flag[it] as Number).toDouble()}" }
        val choices = (flag["enum"] as List<*>?)?.joinToString(", ", ", choices = listOf(", ")") { literal("string", it) } ?: ""
        lines += "        \"$name\" to FlagStore.Declared(\"$type\", $values$bounds$choices),"
    }
    if (flags.isNotEmpty()) lines += "    ))"
    for ((name, flag) in flags) {
        flag as Map<*, *>
        val description = (flag["description"] as String?)?.replace("*/", "*\\/")?.replace(Regex("\\s+"), " ")?.trim()
        lines += ""
        if (!description.isNullOrEmpty()) lines += "    /** $description */"
        val type = types.getValue(flag["type"] as String)
        lines += "    val ${property(name.toString())}: $type get() = store.value(\"$name\") as $type"
    }
    lines += "}"
    return lines.joinToString("\n", postfix = "\n")
}

val flags by tasks.registering {
    description = "Generates the Flags object from the flags declared in swhurl.yaml."
    val input = layout.projectDirectory.file("swhurl.yaml")
    val output = layout.buildDirectory.dir("generated/flags")
    inputs.file(input)
    outputs.dir(output)
    doLast {
        val file = output.get().file("com/swhurl/app/Flags.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(generate(input.asFile.reader().use { Yaml().load<Any?>(it) }))
    }
}
