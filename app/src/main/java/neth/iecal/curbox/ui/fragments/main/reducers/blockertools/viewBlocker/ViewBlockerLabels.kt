package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker

/**
 * Shared extractor used both in [ViewBlockerFragment] (to render chips) and in
 * the Anti-Modifications group picker (so the user can protect custom rules
 * by a human label). The rule string itself is the stable identifier.
 */
object ViewBlockerLabels {
    fun labelFor(ruleString: String): String {
        val cleaned = ruleString.removePrefix("!DISABLED!")
        if (cleaned.contains("##")) {
            val parts = cleaned.split("##")
            for (part in parts) {
                if (part.startsWith("comment=")) return part.removePrefix("comment=")
            }
            val pkg = parts.getOrNull(0)?.substringAfterLast(".") ?: "rule"
            val selector = parts.getOrNull(1) ?: ""
            return "$pkg: $selector"
        }

        val commentMatch = Regex("""comment:(?:"([^"]*)"|(\S+))""").find(cleaned)
        if (commentMatch != null) {
            return commentMatch.groupValues[1].ifEmpty { commentMatch.groupValues[2] }
        }

        val pkgMatch = Regex("""pkg:(\S+)""").find(cleaned)
        val pkgShort = pkgMatch?.groupValues?.get(1)?.substringAfterLast(".") ?: "rule"
        val firstToken = cleaned.trim().split(" ").firstOrNull { !it.startsWith("pkg:") } ?: ""
        return if (firstToken.isEmpty()) pkgShort else "$pkgShort: $firstToken"
    }
}
