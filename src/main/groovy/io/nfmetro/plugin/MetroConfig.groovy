package io.nfmetro.plugin

import groovy.transform.ToString
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

/**
 * The {@code metro} config scope.
 *
 * Declaring it as a {@link ConfigScope} makes Nextflow recognise the
 * {@code metro.*} options (no "Unrecognized config option" warnings) and is
 * the single source of truth for the option names.
 *
 * Mode selection:
 *  - {@code server} + {@code map} -> central mode (register the map on a
 *    persistent server and stream to the run's endpoint)
 *  - else {@code url}             -> attach mode (POST to an existing server)
 *  - else {@code map}            -> managed mode (the plugin spawns the server)
 *  - else                        -> no-op
 *
 *     metro {
 *         map  = 'assets/metro_map.mmd'   // managed mode
 *         open = true
 *     }
 */
@ScopeName('metro')
@ToString(includeNames = true)
class MetroConfig implements ConfigScope {

    @ConfigOption
    @Description('Events endpoint of an already-running nf-metro server (attach mode), e.g. http://localhost:8080/events')
    String url = ''

    @ConfigOption
    @Description('Base URL of a persistent nf-metro serve-multi server (central mode); the run registers its map there')
    String server = ''

    @ConfigOption
    @Description('Path to the metro-map .mmd; used by managed mode (spawn a server) and central mode (register on the server)')
    String map = ''

    @ConfigOption
    @Description('Port for the managed server (0 or unset picks a free port)')
    int port = 0

    @ConfigOption
    @Description('Token guarding the events endpoint (auto-generated in managed mode when unset)')
    String token = ''

    @ConfigOption
    @Description('Open the live map in a browser when the run starts (managed mode)')
    boolean open

    @ConfigOption
    @Description('nf-metro executable used in managed mode (default: resolved from PATH)')
    String binary = 'nf-metro'

    MetroConfig() {}

    MetroConfig(Map opts) {
        this.url = (opts.url ?: '') as String
        this.server = (opts.server ?: '') as String
        this.map = (opts.map ?: '') as String
        this.port = (opts.port ?: 0) as int
        this.token = (opts.token ?: '') as String
        this.open = opts.open as boolean
        this.binary = (opts.binary ?: 'nf-metro') as String
    }

    boolean isCentral() { (server as Boolean) && (map as Boolean) }
    boolean isAttach() { !central && (url as Boolean) }
    boolean isManaged() { !central && !url && (map as Boolean) }
    boolean isActive() { central || attach || managed }
}
