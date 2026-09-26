package com.yanjiyu.terminalspike.core.model

/** TCP forwarding on the authenticated SSH transport, including the companion of a Mosh session. */
enum class PortForwardDirection { LOCAL, REMOTE }

data class PortForwardRule(
    val direction: PortForwardDirection = PortForwardDirection.LOCAL,
    val bindAddress: String = "127.0.0.1",
    val listenPort: Int,
    val destinationHost: String = "127.0.0.1",
    val destinationPort: Int,
    val enabled: Boolean = true,
) {
    init {
        require(bindAddress in BIND_ADDRESSES) { "Choose a loopback or all-interfaces listen address." }
        requirePort(listenPort, "listen port")
        requireHost(destinationHost, "destination host")
        requirePort(destinationPort, "destination port")
    }

    companion object {
        val BIND_ADDRESSES = listOf("127.0.0.1", "::1", "0.0.0.0", "::")
    }
}

/** Bounded, versioned storage shared by Room and portable backups; host validation excludes tabs. */
object PortForwardRules {
    const val MAX_RULES = 16
    private const val MAX_ENCODED_LENGTH = 8192

    fun validate(rules: List<PortForwardRule>) {
        require(rules.size <= MAX_RULES) { "At most 16 port forwards are supported per connection." }
        val active = rules.filter { it.enabled }
        active.forEachIndexed { index, rule ->
            require(active.take(index).none {
                it.direction == rule.direction && it.listenPort == rule.listenPort &&
                    (it.bindAddress == rule.bindAddress ||
                        it.bindAddress in listOf("0.0.0.0", "::") ||
                        rule.bindAddress in listOf("0.0.0.0", "::"))
            }) { "Enabled port forwards have overlapping listen ports." }
        }
    }

    fun encode(rules: List<PortForwardRule>): String {
        validate(rules)
        return rules.joinToString("\n") {
            listOf("1", it.direction.name, it.bindAddress, it.listenPort.toString(),
                it.destinationHost, it.destinationPort.toString(), if (it.enabled) "1" else "0")
                .joinToString("\t")
        }
    }

    fun decode(value: String): List<PortForwardRule> {
        require(value.length <= MAX_ENCODED_LENGTH) { "Port forwarding data is too large." }
        if (value.isEmpty()) return emptyList()
        val lines = value.split('\n')
        require(lines.size <= MAX_RULES)
        return lines.map { line ->
            val fields = line.split('\t')
            require(fields.size == 7 && fields[0] == "1" && fields[6] in listOf("0", "1"))
            PortForwardRule(PortForwardDirection.valueOf(fields[1]), fields[2], fields[3].toInt(),
                fields[4], fields[5].toInt(), fields[6] == "1")
        }.also(::validate)
    }
}
