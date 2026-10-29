package com.yugahashimoto.andcode.feature.terminal

/**
 * Finds the ports of servers a program says it is serving on this device, from what the terminal
 * shows. Dev servers announce themselves in a few common ways ("Local: http://localhost:5173/",
 * "Serving HTTP on 0.0.0.0 port 8000", "listening on :3000"); that is all this recognises.
 *
 * What it finds is a *claim* made by program output, or by a command that was typed and echoed - so
 * a port found here is only worth showing once it has answered a probe (see [DevServerTracker]).
 * Only the port number is taken from the text; the host is never reused, so output cannot point
 * the in-app browser anywhere but this device.
 */
object DevServerDetector {
    private const val LOCAL_HOST = """(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?\])"""

    // A sixth digit, or a dotted quad such as 192.168.1.20, must not be read as a shorter port.
    private const val PORT = """(\d{1,5})(?!\d|\.\d|:\d)"""

    private val URL = Regex("""https?://$LOCAL_HOST:$PORT""", RegexOption.IGNORE_CASE)
    private val LISTENING =
        Regex("""\blisten(?:ing|s)?\s+(?:on|at)\s+(?:port\s+)?(?:(?:$LOCAL_HOST|\*)?:)?$PORT""", RegexOption.IGNORE_CASE)

    private val VALID_PORTS = 1..65535

    /** Every distinct port mentioned in [lines], in the order it first appears. */
    fun portsIn(lines: List<String>): List<Int> {
        val found = LinkedHashSet<Int>()
        for (line in lines) {
            if (!mightMentionAServer(line)) continue
            (URL.findAll(line) + LISTENING.findAll(line))
                .sortedBy { it.range.first }
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in VALID_PORTS }
                .forEach(found::add)
        }
        return found.toList()
    }

    // Output is scanned after every burst of it, so skip the two regexes for the lines that cannot match.
    private fun mightMentionAServer(line: String): Boolean = line.contains("://") || line.contains("isten", ignoreCase = true)
}
