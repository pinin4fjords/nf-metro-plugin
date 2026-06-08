package io.nfmetro.plugin

import nextflow.trace.TraceRecord
import spock.lang.Specification

class MetroObserverTest extends Specification {

    private static TraceRecord trace(Map fields) {
        def tr = new TraceRecord()
        fields.each { k, v -> tr.put(k as String, v) }
        return tr
    }

    def 'maps a submitted task to a weblog payload'() {
        given:
        def tr = trace([task_id: 7, process: 'NFCORE_RNASEQ:RNASEQ:TRIMGALORE', status: 'SUBMITTED'])

        when:
        def p = MetroObserver.weblogPayload('process_submitted', tr, 'SUBMITTED')

        then:
        p.event == 'process_submitted'
        p.trace.task_id == 7
        p.trace.process == 'NFCORE_RNASEQ:RNASEQ:TRIMGALORE'
        p.trace.status == 'SUBMITTED'
    }

    def 'maps a started task to RUNNING'() {
        given:
        def tr = trace([task_id: 3, process: 'FASTQC', status: 'RUNNING'])

        when:
        def p = MetroObserver.weblogPayload('process_started', tr, 'RUNNING')

        then:
        p.event == 'process_started'
        p.trace.task_id == 3
        p.trace.process == 'FASTQC'
        p.trace.status == 'RUNNING'
    }

    def 'caller-supplied status wins over the trace status'() {
        given:
        def tr = trace([task_id: 9, process: 'MULTIQC', status: 'RUNNING'])

        when:
        def p = MetroObserver.weblogPayload('process_completed', tr, 'COMPLETED')

        then:
        p.trace.status == 'COMPLETED'
    }

    def 'tolerates a missing process and non-numeric task id'() {
        when:
        def p = MetroObserver.weblogPayload('process_submitted', trace([:]), 'SUBMITTED')

        then:
        p.trace.process == ''
        p.trace.task_id == -1
    }

    def 'survives a null trace without throwing'() {
        when:
        def p = MetroObserver.weblogPayload('error', null, 'FAILED')

        then:
        p.event == 'error'
        p.trace.process == ''
        p.trace.task_id == -1
    }
}

class MetroConfigTest extends Specification {

    def 'url selects attach mode'() {
        given:
        def c = new MetroConfig(url: 'http://localhost:8090/events')

        expect:
        c.attach
        !c.managed
        c.active
    }

    def 'map without url selects managed mode'() {
        given:
        def c = new MetroConfig(map: '/tmp/pipeline.mmd')

        expect:
        !c.attach
        c.managed
        c.active
    }

    def 'url wins over map'() {
        given:
        def c = new MetroConfig(url: 'http://x/events', map: '/tmp/p.mmd')

        expect:
        c.attach
        !c.managed
    }

    def 'empty config is a no-op'() {
        expect:
        !new MetroConfig().active
    }

    def 'freePort returns a usable port'() {
        expect:
        MetroObserverFactory.freePort() > 0
    }
}
