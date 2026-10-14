package com.swhurl.app

import io.micronaut.context.annotation.Context
import io.micronaut.json.JsonMapper
import io.nats.client.api.KeyValueEntry
import io.nats.client.api.KeyValueOperation
import io.nats.client.api.KeyValueWatcher
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

// Watches this app's feature flag overrides on the platform's NATS (bucket flags-<env>, keys
// <env>.<app>.<flag>) from start-up, sharing the events connection. The watch first delivers every
// stored override, so one survives a restart; /healthz reports not ready until then (at most 5 s).
@Context
class FlagWatcher(private val events: Events, private val platform: Platform, private val json: JsonMapper) {
    private val log = LoggerFactory.getLogger(FlagWatcher::class.java)

    @PostConstruct
    fun start() = watch(Flags.store)

    fun watch(store: FlagStore) {
        val env = platform.setting("SWHURL_ENV")
        val app = platform.setting("SWHURL_APP")
        if (!events.enabled() || app == null || !store.startWatching()) return
        val prefix = "$env.$app."
        thread(isDaemon = true, name = "flag-overrides-timeout") {
            Thread.sleep(LOAD_TIMEOUT_MS)
            if (!store.loaded) log.warn("flag overrides not loaded after $LOAD_TIMEOUT_MS ms: using declared values")
            store.loaded = true
        }
        thread(isDaemon = true, name = "flag-overrides") {
            while (true) {
                try {
                    // The client keeps the watch through reconnects once it is set up.
                    events.connection().keyValue("flags-$env").watch("$prefix>", object : KeyValueWatcher {
                        override fun watch(entry: KeyValueEntry) {
                            val name = entry.key.removePrefix(prefix)
                            val bytes = entry.value
                            val value = if (entry.operation == KeyValueOperation.PUT && bytes != null) {
                                try {
                                    json.readValue(bytes, Any::class.java)
                                } catch (error: Exception) {
                                    log.warn("override for $name is not JSON: ignored")
                                    return
                                }
                            } else null
                            val problem = store.apply(name, value)
                            if (problem.isNotEmpty()) log.warn(problem)
                            else log.info(if (value == null) "flag override cleared: $name" else "flag override set: $name = $value")
                        }

                        override fun endOfData() {
                            store.loaded = true
                        }
                    })
                    return@thread
                } catch (error: Exception) {
                    log.warn("flag overrides: ${error.message}; trying again")
                    Thread.sleep(5_000)
                }
            }
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
