/**
 * Off-hub test runner for the Rheem EcoNet drivers.
 *
 * Runs every method whose name starts with "test" in the suites below, each on a
 * fresh suite instance, and exits non-zero if any fail. Plain Groovy 2.4 asserts —
 * no JUnit — so the only dependency is the groovy-all jar (see run.sh).
 *
 * Usage: tests/run.sh [name-filter]
 */
def suites = [SelectionTests, LoginTests, ThermostatTests, WaterHeaterTests, LogPrivacyTests, SurfaceTests]
def filter = args ? args[0] : null

int passed = 0
def failures = []
suites.each { Class suite ->
    suite.declaredMethods.findAll { it.name.startsWith("test") }.sort { it.name }.each { m ->
        def label = "${suite.simpleName}.${m.name}"
        if (filter && !label.toLowerCase().contains(filter.toLowerCase())) return
        try {
            m.invoke(suite.newInstance())
            passed++
        } catch (java.lang.reflect.InvocationTargetException e) {
            failures << [label: label, error: e.targetException]
        }
    }
}

failures.each { f ->
    println "FAIL ${f.label}"
    println f.error.toString().readLines().take(40).collect { "    " + it }.join("\n")
    if (!(f.error instanceof AssertionError)) {
        f.error.stackTrace.findAll { it.fileName?.endsWith(".groovy") }.take(8).each { println "      at ${it}" }
    }
}
println "\n${passed} passed, ${failures.size()} failed"
System.exit(failures ? 1 : 0)
