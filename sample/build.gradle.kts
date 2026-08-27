// Root build for the Aalekh demo consumer. Configures the plugin exactly the way the docs describe,
// so the sample doubles as a living, runnable example of the `aalekh { }` DSL.
aalekh {
    openBrowserAfterReport.set(false)
    exportMetrics.set(true)

    // A clean one-way layering: presentation -> data -> domain.
    layers {
        layer("domain") {
            modules(":core:domain")
        }
        layer("data") {
            modules(":core:data")
            canOnlyDependOn("domain")
        }
        layer("presentation") {
            modules(":app", ":feature:login")
            canOnlyDependOn("domain", "data")
        }
    }

    // Ownership overlay for the HTML report's team view.
    teams {
        team("core-team") { modules(":core:**") }
        team("app-team") { modules(":app", ":feature:**") }
    }

    rules {
        noOrphanModules()
        // `:core` and `:feature` are empty structural parents Gradle creates for the nested paths;
        // suppress the orphan rule for them (a small demo of the gradual-adoption `suppressFor`).
        rule("no-orphan-modules") {
            suppressFor(":core")
            suppressFor(":feature")
        }
        maxGraphHeight(6)
        // The domain layer must never reach back up into the app, even transitively.
        forbidReachable(from = ":core:domain", to = ":app", because = "domain stays independent of the app")
    }

    // Narrow the exported Mermaid/DOT diagram to the login feature and its neighbours.
    mermaid {
        focus(":feature:login")
        depth(1)
    }
}
