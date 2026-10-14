package com.swhurl.app

// The values of this app's feature flags. The Flags object, generated from swhurl.yaml, reads them here:
// a flag is the value swhurl.yaml declares for this environment (SWHURL_ENV), otherwise its default.
// https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#feature-flags
class FlagStore(private val declared: Map<String, Declared>, private val env: String? = System.getenv("SWHURL_ENV")) {
    /** One flag as swhurl.yaml declares it: its default and its value for staging and prod, where it has one. */
    data class Declared(val default: Any, val staging: Any? = null, val prod: Any? = null)

    fun value(name: String): Any {
        val flag = declared[name] ?: throw IllegalArgumentException("no flag $name is declared in swhurl.yaml")
        val forEnvironment = when (env) {
            "staging" -> flag.staging
            "prod" -> flag.prod
            else -> null
        }
        return forEnvironment ?: flag.default
    }
}
