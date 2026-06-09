package io.nfmetro.plugin

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Fire-and-forget HTTP client that POSTs weblog-shaped JSON events to an
 * nf-metro live server's {@code /events} endpoint.
 *
 * All failures are swallowed: a down or unreachable server must never fail
 * the pipeline. Events are dispatched on a single-threaded executor so the
 * pipeline's own threads are never blocked on the network, while preserving
 * the order in which events were observed.
 */
@Slf4j
@CompileStatic
class MetroEmitter {

    private final URL eventsUrl
    private final String token
    private final ExecutorService pool = Executors.newSingleThreadExecutor({ Runnable r ->
        def t = new Thread(r, 'nf-metro-emitter')
        t.daemon = true
        return t
    } as java.util.concurrent.ThreadFactory)

    MetroEmitter(String eventsUrl, String token) {
        this.eventsUrl = new URL(eventsUrl)
        this.token = token
    }

    /** The resolved /events URL this emitter posts to. */
    URL getEventsUrl() { eventsUrl }

    /** Queue a weblog event for asynchronous delivery. Never throws. */
    void send(Map payload) {
        try {
            final body = JsonOutput.toJson(payload)
            pool.submit({ post(body) } as Runnable)
        }
        catch (Throwable t) {
            log.debug("nf-metro: failed to queue event: ${t.message}")
        }
    }

    private void post(String body) {
        HttpURLConnection conn = null
        try {
            URL url = eventsUrl
            if (token) {
                final sep = (url.query ? '&' : '?')
                url = new URL("${eventsUrl}${sep}token=${URLEncoder.encode(token, 'UTF-8')}")
            }
            conn = (HttpURLConnection) url.openConnection()
            conn.requestMethod = 'POST'
            conn.doOutput = true
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.setRequestProperty('Content-Type', 'application/json')
            if (token)
                conn.setRequestProperty('X-Metro-Token', token)
            conn.outputStream.withWriter('UTF-8') { it.write(body) }
            conn.responseCode  // read to force the request to be sent
        }
        catch (Throwable t) {
            log.debug("nf-metro: event POST failed: ${t.message}")
        }
        finally {
            try { conn?.disconnect() } catch (Throwable ignored) {}
        }
    }

    /** Drain queued events and shut the dispatch thread down. Never throws. */
    void close() {
        try {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
        catch (Throwable t) {
            log.debug("nf-metro: emitter shutdown interrupted: ${t.message}")
        }
    }
}
