package io.nfmetro.plugin

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

/**
 * nf-metro plugin entry point.
 *
 * Drives nf-metro's live-progress server from a Nextflow run by emitting
 * weblog-compatible task events to either an existing server (attach mode)
 * or a server the plugin spawns itself (managed mode).
 */
@CompileStatic
class MetroPlugin extends BasePlugin {

    MetroPlugin(PluginWrapper wrapper) {
        super(wrapper)
    }
}
