package io.nfmetro.plugin

import java.util.concurrent.TimeUnit

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Manages a self-spawned nf-metro live server subprocess (managed mode).
 *
 * Spawns {@code <binary> serve <map> --port <port> --token <token>}, waits
 * for it to start listening, and tears it down at the end of the run. Every
 * operation fails soft: if the binary is missing or the spawn fails, the
 * caller falls back to a no-op so the pipeline is never broken.
 */
@Slf4j
@CompileStatic
class MetroServerManager {

    private final String binary
    private final String mapPath
    private final int port
    private final String token
    private final String host = '127.0.0.1'

    private Process process

    MetroServerManager(String binary, String mapPath, int port, String token) {
        this.binary = binary
        this.mapPath = mapPath
        this.port = port
        this.token = token
    }

    String getBaseUrl() { "http://${host}:${port}/" }
    String getEventsUrl() { "http://${host}:${port}/events" }

    /**
     * Spawn the server and block until it is listening (or the timeout
     * elapses). Returns true on success; on any failure logs a warning and
     * returns false without throwing.
     */
    boolean start(long timeoutMs = 15000) {
        try {
            final cmd = [binary, 'serve', mapPath, '--port', port.toString(), '--host', host]
            if (token)
                cmd.addAll(['--token', token])
            log.debug("nf-metro: spawning server: ${cmd.join(' ')}")
            final pb = new ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            process = pb.start()
        }
        catch (Throwable t) {
            log.warn("nf-metro: could not spawn '${binary}' (${t.message}); live map disabled. " +
                     "Ensure nf-metro is installed and on PATH, or set metro.binary.")
            process = null
            return false
        }

        if (waitUntilListening(timeoutMs))
            return true

        log.warn("nf-metro: server did not start listening on ${baseUrl} within ${timeoutMs}ms; live map disabled.")
        stop()
        return false
    }

    private boolean waitUntilListening(long timeoutMs) {
        final deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                log.warn("nf-metro: server process exited early (status ${safeExitValue()}).")
                return false
            }
            if (probe())
                return true
            try { Thread.sleep(250) } catch (InterruptedException ignored) { return false }
        }
        return false
    }

    private boolean probe() {
        HttpURLConnection conn = null
        try {
            conn = (HttpURLConnection) new URL(baseUrl).openConnection()
            conn.requestMethod = 'GET'
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            return conn.responseCode == 200
        }
        catch (Throwable ignored) {
            return false
        }
        finally {
            try { conn?.disconnect() } catch (Throwable ignored) {}
        }
    }

    private int safeExitValue() {
        try { return process.exitValue() } catch (Throwable ignored) { return -1 }
    }

    /** Open the live map in the default browser. Never throws. */
    void openBrowser() {
        try {
            if (java.awt.Desktop.isDesktopSupported())
                java.awt.Desktop.desktop.browse(new URI(baseUrl))
        }
        catch (Throwable t) {
            log.debug("nf-metro: could not open browser: ${t.message}")
        }
    }

    /** Stop the subprocess: destroy, then force after a grace. Never throws. */
    void stop() {
        if (process == null)
            return
        try {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
        catch (Throwable t) {
            log.debug("nf-metro: error stopping server: ${t.message}")
            try { process.destroyForcibly() } catch (Throwable ignored) {}
        }
        finally {
            process = null
        }
    }
}
