package io.nfmetro.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceObserverFactoryV2

/**
 * Reads the {@code metro} config scope, resolves the operating mode, and
 * creates the single {@link MetroObserver}. When no mode is configured it
 * logs a warning and registers nothing (a true no-op) so the run is unaffected.
 */
@Slf4j
@CompileStatic
class MetroObserverFactory implements TraceObserverFactoryV2 {

    @Override
    Collection<TraceObserverV2> create(Session session) {
        final config = readConfig(session)
        if (!config.active) {
            log.warn("nf-metro: neither metro.url (attach) nor metro.map (managed) set; live map disabled.")
            return Collections.<TraceObserverV2> emptyList()
        }
        return List.<TraceObserverV2> of(new MetroObserver(config))
    }

    /** Build a {@link MetroConfig} from {@code session.config.metro.*}. */
    static MetroConfig readConfig(Session session) {
        final c = new MetroConfig()
        c.url = nav(session, 'metro.url')
        c.server = nav(session, 'metro.server')
        c.map = nav(session, 'metro.map')
        c.token = nav(session, 'metro.token')
        c.open = (navObj(session, 'metro.open') as Boolean)
        final bin = nav(session, 'metro.binary')
        if (bin) c.binary = bin

        final portObj = navObj(session, 'metro.port')
        c.port = (portObj != null ? (portObj as String).toInteger() : 0)

        // Managed mode needs a concrete port and (optionally) an auto-token.
        if (c.managed) {
            if (c.port <= 0)
                c.port = freePort()
            if (!c.token)
                c.token = UUID.randomUUID().toString().replace('-', '')
        }
        return c
    }

    private static String nav(Session session, String key) {
        final v = navObj(session, key)
        return v == null ? null : (v.toString().trim() ?: null)
    }

    private static Object navObj(Session session, String key) {
        try { return session?.config?.navigate(key) }
        catch (Throwable ignored) { return null }
    }

    /** Bind an ephemeral port, release it, and return the number. */
    static int freePort() {
        ServerSocket s = null
        try {
            s = new ServerSocket(0)
            s.reuseAddress = true
            return s.localPort
        }
        catch (Throwable ignored) {
            return 8090
        }
        finally {
            try { s?.close() } catch (Throwable ignored) {}
        }
    }
}
