package io.nfmetro.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent

/**
 * Emits weblog-compatible task events to an nf-metro live server.
 *
 * Three modes, selected by config (see {@link MetroObserverFactory}):
 *  - central: register {@code metro.map} on a persistent {@code metro.server}
 *    and stream to the run's endpoint
 *  - attach:  POST to an existing server given by {@code metro.url}
 *  - managed: spawn {@code <binary> serve <map>} and POST to it
 *
 * Every callback is fail-soft: an emission or server error must never
 * propagate out and break the pipeline run.
 */
@Slf4j
@CompileStatic
class MetroObserver implements TraceObserverV2 {

    private final MetroConfig config
    private MetroEmitter emitter
    private MetroServerManager server
    private Session session

    MetroObserver(MetroConfig config) {
        this.config = config
    }

    /**
     * Build the weblog payload for a task lifecycle event.
     *
     * Reads the fully-qualified process name, task id and status from the
     * event's {@link TraceRecord} (falling back to the {@code task_id} /
     * {@code process} / {@code status} store keys, which is the canonical
     * weblog field naming), and shapes them exactly like a stock Nextflow
     * {@code -with-weblog} trace record so the existing Python server ingests
     * them unchanged.
     */
    static Map weblogPayload(String event, TraceRecord trace, String status) {
        String process = null
        Object taskId = null
        try { process = trace?.getProcessName() } catch (Throwable ignored) {}
        try { taskId = trace?.getTaskId()?.toString() } catch (Throwable ignored) {}
        if (process == null) process = asString(trace?.get('process'))
        if (taskId == null) taskId = trace?.get('task_id')

        int parsedId = -1
        try { parsedId = (taskId as String)?.toInteger() ?: -1 } catch (Throwable ignored) {}

        return [
            event: event,
            trace: [
                task_id: parsedId,
                process: process ?: '',
                status : status,
            ],
        ]
    }

    private static String asString(Object o) { o == null ? null : o.toString() }

    private void emit(String event, TaskEvent te, String status) {
        if (emitter == null) return
        try {
            emitter.send(weblogPayload(event, te?.trace, status))
        }
        catch (Throwable t) {
            log.debug("nf-metro: failed to emit ${event}: ${t.message}")
        }
    }

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        try {
            if (config.central) {
                final reg = registerOnServer(config.server, config.map, safeRunName(), config.token)
                if (reg != null) {
                    emitter = new MetroEmitter(joinUrl(config.server, (String) reg.get('events')), config.token)
                    announce("registered on ${config.server}; live map: ${joinUrl(config.server, (String) reg.get('view'))}")
                }
                else {
                    announce("could not register map on ${config.server}; live map disabled (see .nextflow.log)")
                }
            }
            else if (config.managed) {
                server = new MetroServerManager(config.binary, config.map, config.port, config.token)
                if (server.start()) {
                    emitter = new MetroEmitter(server.eventsUrl, config.token)
                    announce("live map: ${server.baseUrl}")
                    if (config.open)
                        server.openBrowser()
                }
                else if (config.url) {
                    emitter = new MetroEmitter(config.url, config.token)
                    announce("managed server failed; streaming to ${config.url}")
                }
                else {
                    server = null
                    announce("managed server failed to start; live map disabled (see .nextflow.log)")
                }
            }
            else if (config.url) {
                emitter = new MetroEmitter(config.url, config.token)
                announce("streaming progress to ${config.url}")
            }
        }
        catch (Throwable t) {
            log.warn("nf-metro: initialisation failed (${t.message}); live map disabled.")
            emitter = null
        }
    }

    /**
     * Register the run's map on a persistent {@code serve-multi} server and
     * return its {@code {id, view, events}} reply, or null on any failure
     * (logged, never thrown).
     */
    private static Map registerOnServer(String serverBase, String mapPath, String runName, String token) {
        try {
            final mmd = new File(mapPath).getText('UTF-8')
            String qs = ''
            if (runName)
                qs = '?name=' + URLEncoder.encode(runName, 'UTF-8')
            final conn = (HttpURLConnection) new URL(joinUrl(serverBase, 'maps') + qs).openConnection()
            conn.requestMethod = 'POST'
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.setRequestProperty('Content-Type', 'text/plain; charset=utf-8')
            if (token)
                conn.setRequestProperty('X-Metro-Token', token)
            conn.outputStream.write(mmd.getBytes('UTF-8'))
            conn.outputStream.close()
            if (conn.responseCode != 200) {
                log.warn("nf-metro: ${serverBase}/maps returned ${conn.responseCode}")
                return null
            }
            return (Map) new groovy.json.JsonSlurper().parseText(conn.inputStream.getText('UTF-8'))
        }
        catch (Throwable t) {
            log.warn("nf-metro: failed to register map on ${serverBase} (${t.message})")
            return null
        }
    }

    private static String joinUrl(String base, String path) {
        final b = base.endsWith('/') ? base.substring(0, base.length() - 1) : base
        final p = path.startsWith('/') ? path : ('/' + path)
        return b + p
    }

    /** Surface a one-line notice on the console (stdout) and in the log. */
    private static void announce(String msg) {
        log.info("nf-metro: ${msg}")
        System.out.println("  ▶ nf-metro ${msg}")
        System.out.flush()
    }

    @Override
    void onFlowBegin() {
        if (emitter == null) return
        try {
            emitter.send([event: 'started', runName: safeRunName()])
        }
        catch (Throwable t) {
            log.debug("nf-metro: failed to emit started: ${t.message}")
        }
    }

    private String safeRunName() {
        try { return session?.getRunName() } catch (Throwable ignored) { return null }
    }

    @Override
    void onTaskSubmit(TaskEvent event) { emit('process_submitted', event, 'SUBMITTED') }

    @Override
    void onTaskStart(TaskEvent event) { emit('process_started', event, 'RUNNING') }

    @Override
    void onTaskComplete(TaskEvent event) {
        String status = 'COMPLETED'
        try {
            final s = asString(event?.trace?.get('status'))
            if (s && s.equalsIgnoreCase('FAILED'))
                status = 'FAILED'
        }
        catch (Throwable ignored) {}
        emit('process_completed', event, status)
    }

    @Override
    void onTaskCached(TaskEvent event) { emit('process_completed', event, 'COMPLETED') }

    @Override
    void onFlowComplete() {
        try { if (emitter != null) emitter.send([event: 'completed']) } catch (Throwable ignored) {}
        shutdown()
    }

    @Override
    void onFlowError(TaskEvent event) {
        try { if (emitter != null) emitter.send([event: 'error']) } catch (Throwable ignored) {}
    }

    private void shutdown() {
        try { emitter?.close() } catch (Throwable ignored) {}
        try { server?.stop() } catch (Throwable ignored) {}
    }
}
