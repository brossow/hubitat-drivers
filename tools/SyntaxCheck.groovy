// Parses each file given on the command line through Groovy's CONVERSION phase.
// That catches anything that would stop a driver from saving on the hub (unbalanced
// braces, bad string interpolation, stray tokens) without needing Hubitat's own
// classes such as hubitat.zwave.Command on the classpath. It does not catch
// runtime errors: a misspelled method name still parses.

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases

int failed = 0
args.each { String path ->
    CompilationUnit unit = new CompilationUnit()
    unit.addSource(new File(path))
    try {
        unit.compile(Phases.CONVERSION)
        println "ok      ${path}"
    } catch (Exception e) {
        failed++
        println "FAILED  ${path}"
        println e.message.readLines().drop(1).join("\n")
    }
}

println "${args.size() - failed} of ${args.size()} files parse"
System.exit(failed ? 1 : 0)
