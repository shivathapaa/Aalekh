package com.aalekh.aalekh.gradle.extractor

/**
 * Parses a `CODEOWNERS` file and resolves which owners apply to a directory.
 *
 * Most projects that care about ownership already have this file, so reading it gives Aalekh a
 * team map for free - no `teams { }` block required. A declared `teams { }` entry always wins over
 * it, on the same principle as everywhere else: configuration beats a convention.
 *
 * The supported syntax is the common subset GitHub and GitLab share - one `pattern owner...` rule per
 * line, `#` comments, and **last matching rule wins**, which is the rule both hosts apply. Negation
 * (`!pattern`) and brace expansion are not supported; a rule using them is skipped rather than
 * half-interpreted, since guessing the wrong owner is worse than reporting none.
 */
internal object CodeownersParser {

    /**
     * One ownership rule.
     *
     * @param pattern The path pattern as written, normalised without a leading `/`.
     * @param owners The owners it assigns, e.g. `["@org/team", "person@example.com"]`.
     */
    data class Rule(val pattern: String, val owners: List<String>)

    /** Parses the file into rules, in file order. Comments, blanks, and unsupported rules are dropped. */
    fun parse(lines: List<String>): List<Rule> = lines.mapNotNull { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 2) return@mapNotNull null
        val pattern = parts.first()
        // Negation and brace expansion change which paths match in ways this matcher does not
        // model; a half-applied rule would silently assign the wrong owner.
        if (pattern.startsWith("!") || '{' in pattern) return@mapNotNull null
        Rule(pattern.removePrefix("/"), parts.drop(1))
    }

    /**
     * The owners for a repo-relative directory, or an empty list when no rule matches.
     *
     * Both GitHub and GitLab give precedence to the **last** matching rule in the file, so the scan
     * runs backwards and stops at the first hit.
     */
    fun ownersFor(directory: String, rules: List<Rule>): List<String> =
        rules.lastOrNull { matches(it.pattern, directory) }?.owners.orEmpty()

    /**
     * True when a CODEOWNERS pattern covers a directory.
     *
     * Three shapes cover essentially every real file: `*` matches everything, a pattern ending in `/`
     * or naming a directory matches that directory and everything under it, and a `*`-containing
     * pattern is matched segment-wise like a glob.
     */
    private fun matches(pattern: String, directory: String): Boolean {
        if (pattern == "*") return true
        val normalised = pattern.removeSuffix("/")
        return if ('*' in normalised) {
            val regex = Regex(
                normalised.split("*").joinToString("[^/]*") { Regex.escape(it) } + "(/.*)?"
            )
            regex.matches(directory)
        } else {
            directory == normalised || directory.startsWith("$normalised/")
        }
    }
}
