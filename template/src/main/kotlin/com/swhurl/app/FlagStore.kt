package com.swhurl.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// The values of this app's feature flags. The Flags object, generated from swhurl.yaml, reads them here.
// https://github.com/samclement/swhurl-platform/blob/main/docs/apps.md#feature-flags
//
// A flag is, in order: an override set from the console (watched on the platform's NATS by FlagWatcher,
// in the bucket flags-<env> under <env>.<app>.<flag>), the value swhurl.yaml declares for this
// environment (SWHURL_ENV), its default. Off the platform the declared values apply.
class FlagStore(private val declared: Map<String, Declared>, private val env: String? = System.getenv("SWHURL_ENV")) {
    /** One flag as swhurl.yaml declares it. */
    data class Declared(
        val type: String,
        val default: Any,
        val staging: Any? = null,
        val prod: Any? = null,
        val min: Double? = null,
        val max: Double? = null,
        val choices: List<String>? = null,
    )

    private val overrides = ConcurrentHashMap<String, Any>()
    private val watching = AtomicBoolean()

    /** Whether the stored overrides have arrived (or there is nothing to wait for, or waiting timed out). */
    @Volatile
    var loaded = true
        internal set

    val names: Set<String> get() = declared.keys

    fun value(name: String): Any {
        val flag = declared[name] ?: throw IllegalArgumentException("no flag $name is declared in swhurl.yaml")
        overrides[name]?.let { return it }
        val forEnvironment = when (env) {
            "staging" -> flag.staging
            "prod" -> flag.prod
            else -> null
        }
        return forEnvironment ?: flag.default
    }

    /** Take one watched entry: an override (value) or a cleared one (null). Returns why it was ignored, or "". */
    fun apply(name: String, value: Any?): String {
        val flag = declared[name] ?: return "override for $name, which swhurl.yaml does not declare: ignored"
        if (value == null) {
            overrides.remove(name)
            return ""
        }
        val typed: Any = when (flag.type) {
            "boolean" -> value as? Boolean
            "integer" -> (value as? Number)?.takeIf { (it is Int || it is Long) && it.toLong() in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            "number" -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
            else -> value as? String
        } ?: return "override for $name is not ${if (flag.type == "integer") "an" else "a"} ${flag.type}: ignored"
        if (typed is Number) {
            val number = typed.toDouble()
            if ((flag.min != null && number < flag.min) || (flag.max != null && number > flag.max)) return "override for $name is outside min and max: ignored"
        }
        if (flag.choices != null && typed !in flag.choices) return "override for $name is not one of ${flag.choices.joinToString(", ")}: ignored"
        overrides[name] = typed
        return ""
    }

    /** Called once by FlagWatcher: true if it should start watching (on the platform, with flags declared). */
    internal fun startWatching(): Boolean {
        if (declared.isEmpty() || env !in setOf("staging", "prod") || !watching.compareAndSet(false, true)) return false
        loaded = false
        return true
    }
}
