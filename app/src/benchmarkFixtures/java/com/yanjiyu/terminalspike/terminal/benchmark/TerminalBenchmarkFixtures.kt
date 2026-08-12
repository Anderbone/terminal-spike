package com.yanjiyu.terminalspike.terminal.benchmark

import java.security.MessageDigest

internal enum class TerminalFixtureKind {
    PLAIN,
    ANSI_HEAVY,
    CURSOR_REDRAW,
    FULL_SCREEN_REDRAW,
    CJK_WIDE,
    TMUX_STATUS,
}

internal data class TerminalFixtureSpec(
    val id: String,
    val kind: TerminalFixtureKind,
    val records: Int,
    val minimumRecordBytes: Int,
    val chunkBytes: Int,
    val columns: Int,
    val rows: Int,
) {
    init {
        require(id.matches(Regex("[a-z0-9-]+")))
        require(records >= 10_000)
        require(minimumRecordBytes in 64..8_192)
        require(chunkBytes in 1_024..1_048_576)
        require(columns in 1..512)
        require(rows in 1..256)
    }

    val minimumTotalBytes: Long
        get() = records.toLong() * minimumRecordBytes
}

internal object TerminalBenchmarkFixtureCatalog {
    const val RESOURCE = "terminal-benchmark-fixtures/scenarios.tsv"

    fun load(
        classLoader: ClassLoader = requireNotNull(TerminalBenchmarkFixtureCatalog::class.java.classLoader),
    ): List<TerminalFixtureSpec> =
        requireNotNull(classLoader.getResourceAsStream(RESOURCE)) {
            "Missing test-only terminal fixture catalog $RESOURCE"
        }.bufferedReader(Charsets.UTF_8).use { reader -> parse(reader.readText()) }

    fun parse(catalog: String): List<TerminalFixtureSpec> {
        val specs = catalog.lineSequence().let { lines ->
            lines.map(String::trim)
                .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                .map(::parseLine)
                .toList()
        }
        require(specs.isNotEmpty()) { "Terminal fixture catalog must not be empty" }
        require(specs.map(TerminalFixtureSpec::id).distinct().size == specs.size) {
            "Terminal fixture ids must be unique"
        }
        return specs
    }

    private fun parseLine(line: String): TerminalFixtureSpec {
        val fields = line.split('\t')
        require(fields.size == 7) { "Invalid terminal fixture row: $line" }
        return TerminalFixtureSpec(
            id = fields[0],
            kind = TerminalFixtureKind.valueOf(fields[1]),
            records = fields[2].toInt(),
            minimumRecordBytes = fields[3].toInt(),
            chunkBytes = fields[4].toInt(),
            columns = fields[5].toInt(),
            rows = fields[6].toInt(),
        )
    }
}

internal object TerminalBenchmarkFixtureGenerator {
    fun chunks(
        spec: TerminalFixtureSpec,
        chunkBytes: Int = spec.chunkBytes,
    ): Sequence<ByteArray> {
        require(chunkBytes > 0)
        return sequence {
            var chunk = ByteArray(chunkBytes)
            var used = 0
            repeat(spec.records) { index ->
                val record = record(spec, index).toByteArray(Charsets.UTF_8)
                var sourceOffset = 0
                while (sourceOffset < record.size) {
                    val count = minOf(record.size - sourceOffset, chunk.size - used)
                    record.copyInto(
                        destination = chunk,
                        destinationOffset = used,
                        startIndex = sourceOffset,
                        endIndex = sourceOffset + count,
                    )
                    used += count
                    sourceOffset += count
                    if (used == chunk.size) {
                        yield(chunk)
                        chunk = ByteArray(chunkBytes)
                        used = 0
                    }
                }
            }
            if (used > 0) yield(chunk.copyOf(used))
        }
    }

    fun digest(spec: TerminalFixtureSpec, chunkBytes: Int = spec.chunkBytes): String =
        MessageDigest.getInstance("SHA-256").run {
            chunks(spec, chunkBytes).forEach { chunk -> update(chunk) }
            digest().toHex()
        }

    fun byteCount(spec: TerminalFixtureSpec): Long = chunks(spec).sumOf { chunk -> chunk.size.toLong() }

    private fun record(spec: TerminalFixtureSpec, index: Int): String {
        val row = index % spec.rows + 1
        val column = index * 17 % spec.columns + 1
        val colour = index * 37 % 256
        val (prefix, filler, suffix) = when (spec.kind) {
            TerminalFixtureKind.PLAIN -> Triple(
                "%08d INFO worker=%03d path=/workspace/terminal/src/render/Frame.kt ".format(
                    index,
                    index % 128,
                ),
                "status=ready latency=${index % 997}us ",
                "\r\n",
            )
            TerminalFixtureKind.ANSI_HEAVY -> Triple(
                "\u001B[1;38;5;${colour}m%08d\u001B[0m ".format(index) +
                    "\u001B[48;5;${(colour + 91) % 256}m block \u001B[0m ",
                "\u001B[3${index % 8}mstyled-${index % 113}\u001B[0m ",
                "\u001B[0m\r\n",
            )
            TerminalFixtureKind.CURSOR_REDRAW -> Triple(
                "\u001B[$row;${column}H\u001B[2K\r\u001B[38;5;${colour}m" +
                    "job=${index % 64} progress=${index % 101}% ",
                "cursor-cell-${index % 251} ",
                "\u001B[0m",
            )
            TerminalFixtureKind.FULL_SCREEN_REDRAW -> Triple(
                "\u001B[2J\u001B[Hframe=$index load=${index * 7 % 100}%" +
                    "\u001B[2;1Hpid cpu mem state command" +
                    "\u001B[3;1H${1200 + index % 7000} ${index % 100}.0 ${index % 32}.5 RUN worker" +
                    "\u001B[${spec.rows};1H",
                "redraw-pane-${index % 97} ",
                "\u001B[0m",
            )
            TerminalFixtureKind.CJK_WIDE -> Triple(
                "%08d Unicode 宽字符 你好世界 日本語 한국어 café e\u0301 👩🏽‍💻 🇬🇧 ".format(index),
                "界語終端測試 한글조합 🚀 ",
                "\r\n",
            )
            TerminalFixtureKind.TMUX_STATUS -> Triple(
                "\u001B[${spec.rows};1H\u001B[48;5;${colour};38;5;${(colour + 127) % 256}m" +
                    " session:${index % 32}  window:${index % 12} pane:${index % 8} ",
                "cpu=${index * 13 % 100}% host=dev-${index % 19} ",
                "\u001B[0m\u001B[1;1H",
            )
        }
        return padToMinimumBytes(prefix, filler, suffix, spec.minimumRecordBytes)
    }

    private fun padToMinimumBytes(
        prefix: String,
        filler: String,
        suffix: String,
        minimumBytes: Int,
    ): String = buildString {
        append(prefix)
        while ((this.toString() + suffix).toByteArray(Charsets.UTF_8).size < minimumBytes) {
            append(filler)
        }
        append(suffix)
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
