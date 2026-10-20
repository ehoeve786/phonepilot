package app.pocketpilot.capability.api.shell

/**
 * Read-only adb shell commands that may run without the owner confirming each one (`adb.query`,
 * `adb.logs`). Commands run as an argv list, never through a shell, and both the tool and the
 * privileged service check them here. Anything that changes the phone goes through `shell.exec`.
 */
object AdbQuery {
    const val MAX_ARGS = 32

    /** Arguments are plain words: no spaces, quotes or shell syntax. */
    private val TOKEN = Regex("^[A-Za-z0-9_.,:=/@%+*#\\-]{1,200}$")
    private val NAME = Regex("^[A-Za-z0-9_.\\-]+$")

    /**
     * System services whose dumps hold personal data (messages, contacts, accounts, calls, location,
     * clipboard, credentials). `adb.query` refuses them; `shell.exec` can still read them with consent.
     */
    val PERSONAL_SERVICES =
        setOf(
            "account",
            "clipboard",
            "contacts",
            "content",
            "credential",
            "dbinfo",
            "dropbox",
            "iphonesubinfo",
            "isms",
            "sms",
            "location",
            "usagestats",
            "lock_settings",
            "notification",
            "people",
            "telecom",
            "keystore",
            "android.security.keystore",
            "android.security.legacykeystore",
        )

    private val READ_VERB_PREFIXES = listOf("get", "list", "status", "query", "is-", "has-", "print", "help")
    private val SETTINGS_NAMESPACES = setOf("system", "secure", "global")
    private val IP_WRITES =
        setOf(
            "add",
            "del",
            "delete",
            "set",
            "flush",
            "change",
            "replace",
            "append",
            "prepend",
            "exec",
            "netns",
            "deleteall",
            "monitor",
            "save",
            "restore",
            "-b",
            "-batch",
            "-force",
        )

    /** Options that only ask a dump service for more output. */
    private val READ_DUMP_OPTIONS =
        setOf(
            "-a",
            "-h",
            "-c",
            "--charged",
            "--checkin",
            "--history",
            "--proto",
            "--unfiltered",
        )

    /** Words that make a dump service change state, such as `dumpsys deviceidle force-idle`. */
    private val MUTATING_DUMP_WORDS =
        setOf(
            "force-idle",
            "unforce",
            "enable",
            "disable",
            "step",
            "reset",
            "set",
            "clear",
            "kill",
            "stop",
            "start",
            "add",
            "remove",
            "delete",
            "update",
            "write",
            "reload",
            "trim",
            "reboot",
            "quit",
            "exit",
            "save",
        )
    private val PING_FLAGS = setOf("-c", "-W", "-w", "-i", "-s", "-q", "-n", "-4", "-6")
    private val READABLE_PATHS =
        listOf(
            "/proc/meminfo",
            "/proc/cpuinfo",
            "/proc/loadavg",
            "/proc/uptime",
            "/proc/version",
            "/proc/stat",
            "/proc/pressure/",
            "/proc/net/dev",
            "/sys/class/thermal/",
            "/sys/class/power_supply/",
            "/sys/devices/system/cpu/",
        )
    private val LOGCAT_REFUSED = setOf("-c", "--clear", "-f", "--file", "-r", "-n", "-G", "--buffer-size", "-P", "--prune", "--wrap")

    /** Programs `adb.query` accepts; see [check]. */
    val PROGRAMS =
        listOf(
            "dumpsys",
            "getprop",
            "settings",
            "pm",
            "cmd",
            "am",
            "appops",
            "wm",
            "ime",
            "service",
            "top",
            "ps",
            "df",
            "free",
            "uptime",
            "uname",
            "nproc",
            "getenforce",
            "date",
            "ip",
            "ifconfig",
            "netstat",
            "ping",
            "ping6",
            "cat",
            "ls",
        )

    /** Throws [IllegalArgumentException] unless [argv] is a read-only command `adb.query` may run. */
    fun check(argv: List<String>) {
        checkTokens(argv)
        val args = argv.drop(1)
        when (argv[0]) {
            "dumpsys" -> {
                val service = args.firstOrNull() ?: refuse("Name a service, for example dumpsys battery; dumpsys -l lists them")
                if (service != "-l") checkService(service)
                // Services take their own words after the name; only read-only options and plain words pass.
                args.drop(1).forEach { option ->
                    val plainWord = !option.startsWith("-") && NAME.matches(option) && option !in MUTATING_DUMP_WORDS
                    require(option in READ_DUMP_OPTIONS || plainWord) { "dumpsys $service takes only read-only options, not $option" }
                }
            }

            "getprop" -> {
                require(args.size <= 1) { "getprop takes at most one property name" }
            }

            "settings" -> {
                require(args.firstOrNull() in setOf("get", "list")) { "Only settings get and settings list are read-only" }
                require(args.getOrNull(1) in SETTINGS_NAMESPACES) { "Name a namespace: system, secure or global" }
                require(args.size <= if (args[0] == "get") 3 else 2) { "Too many arguments for settings ${args[0]}" }
            }

            "pm" -> {
                val verb = args.firstOrNull().orEmpty()
                require(verb.startsWith("list") || verb.startsWith("get-") || verb in setOf("path", "dump", "has-feature")) {
                    "Only pm list, path, dump, has-feature and get-* are read-only"
                }
            }

            "cmd" -> {
                val service = args.firstOrNull() ?: refuse("Name a service, for example cmd wifi status")
                checkService(service)
                val verb = args.getOrNull(1).orEmpty()
                val readOnly =
                    READ_VERB_PREFIXES.any(verb::startsWith) ||
                        verb == "dump" ||
                        (service == "deviceidle" && verb == "whitelist" && args.size == 2)
                require(readOnly) { "Only read-only cmd verbs (get, list, status, query, is-, has-, print, dump, help) are allowed" }
            }

            "am" -> {
                require(args.firstOrNull().orEmpty().startsWith("get-")) { "Only am get-* is read-only" }
            }

            "appops" -> {
                require(args.firstOrNull() in setOf("get", "query-op")) { "Only appops get and query-op are read-only" }
            }

            "wm" -> {
                require(args.size == 1 && args[0] in setOf("size", "density")) { "Only wm size and wm density, without values" }
            }

            "ime" -> {
                require(args.firstOrNull() == "list") { "Only ime list is read-only" }
            }

            "service" -> {
                require(args.firstOrNull() in setOf("list", "check")) { "Only service list and service check" }
            }

            "top" -> {
                require("-b" in args && "-n" in args) { "Run top with -b -n 1 so it ends" }
            }

            "date" -> {
                // Shows the phone's clock; -s and a bare date string would set it, so only output options pass.
                require(args.all { it == "-u" || it == "-R" || it.startsWith("+") }) { "date only shows the time; it cannot set it" }
            }

            "ps", "df", "free", "netstat", "uptime", "uname", "nproc", "getenforce" -> {
                require(args.all { it.startsWith("-") || it.startsWith("/") || NAME.matches(it) }) { "Unexpected argument" }
            }

            "ifconfig" -> {
                require(args.size <= 1) { "ifconfig takes at most an interface name; it cannot change one here" }
            }

            "ip" -> {
                // iproute2 takes any prefix of a verb, so `ip link s wlan0 down` is `set`; a word that starts a write verb is refused.
                require(args.none { word -> !word.startsWith("-") && IP_WRITES.any { it.startsWith(word) } }) {
                    "Only ip commands that show state are allowed"
                }
            }

            "ping", "ping6" -> {
                checkPing(args)
            }

            "cat" -> {
                require(args.isNotEmpty()) { "Name a file" }
                args.forEach(::checkPath)
            }

            "ls" -> {
                args.filterNot { it.startsWith("-") }.forEach(::checkPath)
            }

            else -> {
                refuse("${argv[0]} is not a read-only command here. Allowed: ${PROGRAMS.joinToString()}")
            }
        }
    }

    /** The single-letter flags in one argument, so `-dc` counts as `-d` and `-c`. */
    private fun shortFlags(arg: String): Set<Char> =
        if (arg.startsWith("-") && !arg.startsWith("--")) arg.drop(1).takeWhile { it.isLetter() }.toSet() else emptySet()

    /** True for a logcat argument that changes the logs, whether it is written alone, bundled or with `=`. */
    private fun refusedLogcat(arg: String): Boolean =
        arg.substringBefore('=') in LOGCAT_REFUSED || shortFlags(arg).any { "-$it" in LOGCAT_REFUSED }

    /** Throws unless [argv] reads logs: `logcat -d ...`, `pidof <package>` or `dumpsys dropbox`. */
    fun checkLogs(argv: List<String>) {
        checkTokens(argv)
        val args = argv.drop(1)
        when (argv[0]) {
            "logcat" -> {
                require(args.any { it == "--dump" || 'd' in shortFlags(it) }) { "logcat needs -d so it ends" }
                require(args.none { refusedLogcat(it) }) { "That logcat option changes the logs" }
            }

            "pidof" -> {
                require(args.size == 1 && NAME.matches(args[0])) { "pidof takes one package name" }
            }

            "dumpsys" -> {
                require(args.firstOrNull() == "dropbox") { "Only dumpsys dropbox reads crash reports" }
            }

            else -> {
                refuse("${argv[0]} does not read logs")
            }
        }
    }

    /** True when either [check] or [checkLogs] accepts [argv]; the privileged service runs nothing else. */
    fun allowed(argv: List<String>): Boolean = runCatching { check(argv) }.isSuccess || runCatching { checkLogs(argv) }.isSuccess

    /**
     * Splits a command line into words. A leading `adb shell` is dropped, since models often copy it
     * from documentation.
     */
    fun split(command: String): List<String> {
        val words = command.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return when {
            words.take(2) == listOf("adb", "shell") -> words.drop(2)
            words.firstOrNull() == "shell" -> words.drop(1)
            else -> words
        }
    }

    private fun checkTokens(argv: List<String>) {
        require(argv.isNotEmpty()) { "Give a command" }
        require(argv.size <= MAX_ARGS) { "At most $MAX_ARGS words" }
        argv.forEach { require(TOKEN.matches(it)) { "Words may not contain spaces, quotes or shell syntax: $it" } }
    }

    private fun checkService(name: String) {
        require(NAME.matches(name)) { "Not a service name: $name" }
        require(name !in PERSONAL_SERVICES) { "$name holds personal data; it can only be read with shell.exec, which the owner confirms" }
    }

    private fun checkPing(args: List<String>) {
        var i = 0
        var count: Int? = null
        var host: String? = null
        while (i < args.size) {
            val arg = args[i]
            when {
                arg in setOf("-q", "-n", "-4", "-6") -> {
                    i++
                }

                arg in PING_FLAGS -> {
                    val value = args.getOrNull(i + 1)?.toDoubleOrNull() ?: refuse("$arg needs a number")
                    if (arg == "-c") count = value.toInt()
                    if (arg == "-i") require(value >= 0.2) { "-i must be at least 0.2" }
                    i += 2
                }

                arg.startsWith("-") -> {
                    refuse("ping option $arg is not allowed; use ${PING_FLAGS.joinToString()}")
                }

                else -> {
                    require(host == null) { "ping takes one host" }
                    host = arg
                    i++
                }
            }
        }
        require(host != null) { "Name a host to ping" }
        require(count != null && count in 1..10) { "Give -c with 1 to 10 pings" }
    }

    private fun checkPath(path: String) {
        require(
            ".." !in path && READABLE_PATHS.any { path == it || (it.endsWith("/") && path.startsWith(it)) || path == it.trimEnd('/') },
        ) {
            "Only system status files can be read: ${READABLE_PATHS.joinToString()}"
        }
    }

    private fun refuse(message: String): Nothing = throw IllegalArgumentException(message)
}
