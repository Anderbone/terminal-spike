package com.yanjiyu.terminalspike.settings

import android.content.ContentResolver
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.yanjiyu.terminalspike.core.backup.BackupCustomFont
import com.yanjiyu.terminalspike.core.backup.BackupCustomFontPersistence
import com.yanjiyu.terminalspike.core.backup.BackupPayloadFormat
import com.yanjiyu.terminalspike.core.backup.PreparedBackupCustomFontImport
import com.yanjiyu.terminalspike.core.backup.customFontBackupRecordId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class ImportedTerminalFont(
    val id: String,
    val displayName: String,
    internal val file: File,
)

/** Copies user-selected TTF/OTF data into private app storage after bounded format validation. */
class CustomTerminalFontStore(context: Context) : BackupCustomFontPersistence {
    private val applicationContext = context.applicationContext
    private val directory = applicationContext.filesDir.resolve(DIRECTORY_NAME)
    private val monitor = Any()

    fun list(): List<ImportedTerminalFont> = synchronized(monitor) { listUnlocked() }

    fun resolve(fontId: String): File? = list().firstOrNull { it.id == fontId }?.file

    @Throws(IOException::class, IllegalArgumentException::class)
    fun import(uri: Uri): ImportedTerminalFont = synchronized(monitor) {
        directory.mkdirs()
        require(directory.isDirectory) { "Private font storage is unavailable." }
        val displayName = queryDisplayName(applicationContext.contentResolver, uri)
        val temporary = File.createTempFile("font-import-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= MAX_FONT_BYTES) { "The selected font is larger than 16 MiB." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("The selected document could not be opened.")
            require(total >= MIN_FONT_BYTES) { "The selected document is not a supported font." }
            require(hasSupportedHeader(temporary)) { "Choose a TrueType, OpenType, or font collection file." }
            val typeface = Typeface.Builder(temporary).build()
            require(isMonospaceTerminalTypeface(typeface)) {
                "Choose a monospace font so terminal columns stay aligned."
            }

            val id = CUSTOM_ID_PREFIX + digest.digest().toHex()
            listUnlocked().firstOrNull { it.id == id }?.let { existing ->
                temporary.delete()
                return existing
            }
            val encodedName = Base64.encodeToString(
                displayName.take(MAX_DISPLAY_NAME_CHARACTERS).encodeToByteArray(),
                Base64.NO_WRAP or Base64.URL_SAFE,
            ).trimEnd('=')
            val target = directory.resolve("$id$NAME_SEPARATOR$encodedName.font")
            check(temporary.renameTo(target)) { "The imported font could not be committed." }
            return ImportedTerminalFont(id = id, displayName = displayName, file = target)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    /** Reads only profile-referenced content-addressed files for an explicit portable export. */
    fun readForBackup(fontIds: Set<String>): List<BackupCustomFont> = synchronized(monitor) {
        val records = listUnlocked().associateBy(ImportedTerminalFont::id)
        fontIds.sorted().map { fontId ->
            val imported = records[fontId]
                ?: throw IOException("A selected custom font file is unavailable.")
            validateStoredFont(imported.file, fontId)
            val bytes = imported.file.readBytes()
            try {
                BackupCustomFont.takeOwnership(
                    recordId = customFontBackupRecordId(fontId),
                    fontId = fontId,
                    displayName = imported.displayName,
                    bytes = bytes,
                )
            } catch (error: Throwable) {
                bytes.fill(0)
                throw error
            }
        }
    }

    /** Validates and durably journals new files before Room is allowed to reference them. */
    override fun prepare(fonts: List<BackupCustomFont>): PreparedBackupCustomFontImport =
        synchronized(monitor) {
            if (fonts.isEmpty()) return@synchronized PreparedBackupCustomFontImport.NONE
            require(!journalFile().exists()) { "A previous custom-font restore still needs reconciliation." }
            directory.mkdirs()
            require(directory.isDirectory) { "Private font storage is unavailable." }
            val existing = listUnlocked().associateBy(ImportedTerminalFont::id)
            val staged = mutableListOf<StagedFont>()
            try {
                fonts.distinctBy(BackupCustomFont::fontId).forEach { font ->
                    existing[font.fontId]?.let { installed ->
                        validateStoredFont(installed.file, font.fontId)
                        return@forEach
                    }
                    val target = targetFile(font.fontId, font.displayName)
                    val temporary = directory.resolve(".restore-${font.fontId}.part")
                    temporary.delete()
                    try {
                        FileOutputStream(temporary).use { output ->
                            font.writeBytes { bytes -> output.write(bytes) }
                            output.fd.sync()
                        }
                        validateStoredFont(temporary, font.fontId)
                        staged += StagedFont(font, temporary, target)
                    } catch (error: Throwable) {
                        temporary.delete()
                        throw error
                    }
                }
                if (staged.isEmpty()) return@synchronized PreparedBackupCustomFontImport.NONE

                writeJournal(
                    staged.map { item -> FontJournalEntry(item.font.fontId, item.target.name) },
                )
                val created = mutableListOf<File>()
                try {
                    staged.forEach { item ->
                        check(!item.target.exists() && item.temporary.renameTo(item.target)) {
                            "An imported custom font could not be committed."
                        }
                        created += item.target
                    }
                } catch (error: Throwable) {
                    created.forEach(File::delete)
                    journalFile().delete()
                    throw error
                } finally {
                    staged.forEach { it.temporary.delete() }
                }
                PreparedFontImport(created.map(File::getName))
            } catch (error: Throwable) {
                staged.forEach { it.temporary.delete() }
                throw error
            }
        }

    /** Resolves a process-death journal only after Room has become authoritative. */
    override fun reconcilePending(referencedFontIds: Set<String>) = synchronized(monitor) {
        val journal = readJournal() ?: return@synchronized
        journal.forEach { entry ->
            val target = directory.resolve(entry.targetFileName)
            val staged = directory.resolve(".restore-${entry.fontId}.part")
            if (entry.fontId in referencedFontIds) {
                if (!target.exists()) {
                    check(staged.exists() && staged.renameTo(target)) {
                        "A referenced custom-font restore file could not be finalized."
                    }
                }
                validateStoredFont(target, entry.fontId)
                staged.delete()
            } else {
                target.delete()
                staged.delete()
            }
        }
        check(journalFile().delete() || !journalFile().exists()) {
            "The custom-font restore journal could not be cleared."
        }
    }

    private inner class PreparedFontImport(
        private val createdFileNames: List<String>,
    ) : PreparedBackupCustomFontImport {
        private val completed = AtomicBoolean(false)

        override fun commit() = synchronized(monitor) {
            if (!completed.compareAndSet(false, true)) return@synchronized
            check(journalFile().delete() || !journalFile().exists()) {
                "The custom-font restore journal could not be cleared."
            }
        }

        override fun rollback() = synchronized(monitor) {
            if (!completed.compareAndSet(false, true)) return@synchronized
            createdFileNames.forEach { fileName -> directory.resolve(fileName).delete() }
            journalFile().delete()
        }
    }

    private fun listUnlocked(): List<ImportedTerminalFont> = directory.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isFile)
        .mapNotNull(::decodeRecord)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ImportedTerminalFont::displayName))
        .toList()

    private fun validateStoredFont(file: File, expectedId: String) {
        require(file.length() in MIN_FONT_BYTES..BackupPayloadFormat.MAX_CUSTOM_FONT_BYTES.toLong()) {
            "A custom font file is outside the supported size range."
        }
        require(hasSupportedHeader(file)) { "A custom font file has an unsupported format." }
        val typeface = Typeface.Builder(file).build()
        require(isMonospaceTerminalTypeface(typeface)) {
            "A custom font is not monospace."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        require(expectedId == CUSTOM_ID_PREFIX + digest.digest().toHex()) {
            "A custom font file failed its content hash check."
        }
    }

    private fun targetFile(fontId: String, displayName: String): File {
        val encodedName = Base64.encodeToString(
            displayName.take(MAX_DISPLAY_NAME_CHARACTERS).encodeToByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE,
        ).trimEnd('=')
        return directory.resolve("$fontId$NAME_SEPARATOR$encodedName.font")
    }

    private fun writeJournal(entries: List<FontJournalEntry>) {
        val temporary = directory.resolve("$JOURNAL_FILE_NAME.part")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(
                    entries.sortedBy(FontJournalEntry::fontId)
                        .joinToString(separator = "\n", postfix = "\n") { entry ->
                            "${entry.fontId}\t${entry.targetFileName}"
                        }
                        .encodeToByteArray(),
                )
                output.fd.sync()
            }
            check(temporary.renameTo(journalFile())) { "The custom-font restore journal could not be committed." }
        } finally {
            temporary.delete()
        }
    }

    private fun readJournal(): List<FontJournalEntry>? {
        val file = journalFile()
        if (!file.exists()) return null
        return file.readLines().filter(String::isNotBlank).map { line ->
            val fields = line.split('\t')
            require(fields.size == 2) { "The custom-font restore journal is malformed." }
            val fontId = fields[0]
            val targetFileName = fields[1]
            require(fontId.startsWith(CUSTOM_ID_PREFIX) && fontId.length == CUSTOM_ID_LENGTH) {
                "The custom-font restore journal is malformed."
            }
            require(
                targetFileName == File(targetFileName).name &&
                    decodeRecord(directory.resolve(targetFileName))?.id == fontId
            ) { "The custom-font restore journal contains an unsafe target." }
            FontJournalEntry(fontId, targetFileName)
        }.distinctBy(FontJournalEntry::fontId)
    }

    private fun journalFile(): File = directory.resolve(JOURNAL_FILE_NAME)

    private data class StagedFont(
        val font: BackupCustomFont,
        val temporary: File,
        val target: File,
    )

    private data class FontJournalEntry(
        val fontId: String,
        val targetFileName: String,
    )

    private fun decodeRecord(file: File): ImportedTerminalFont? {
        val stem = file.name.removeSuffix(".font")
        val id = stem.substringBefore(NAME_SEPARATOR)
        if (!id.startsWith(CUSTOM_ID_PREFIX) || id.length != CUSTOM_ID_LENGTH) return null
        if (!id.removePrefix(CUSTOM_ID_PREFIX).all { it in "0123456789abcdef" }) return null
        val encodedName = stem.substringAfter(NAME_SEPARATOR, missingDelimiterValue = "")
        val displayName = runCatching {
            Base64.decode(encodedName, Base64.NO_WRAP or Base64.URL_SAFE).toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isSafeDisplayName() } ?: "Imported font"
        return ImportedTerminalFont(id, displayName, file)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
        val candidate = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return candidate
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isSafeDisplayName() }
            ?: "Imported font"
    }

    private fun String.isSafeDisplayName(): Boolean =
        isNotBlank() && length <= MAX_DISPLAY_NAME_CHARACTERS && none(Char::isISOControl)

    private fun hasSupportedHeader(file: File): Boolean {
        val header = ByteArray(4)
        val count = file.inputStream().use { it.read(header) }
        if (count != header.size) return false
        return header.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)) ||
            header.contentEquals("OTTO".encodeToByteArray()) ||
            header.contentEquals("true".encodeToByteArray()) ||
            header.contentEquals("ttcf".encodeToByteArray())
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
    }

    companion object {
        private const val DIRECTORY_NAME = "terminal-fonts"
        private const val CUSTOM_ID_PREFIX = "custom_"
        private const val CUSTOM_ID_LENGTH = CUSTOM_ID_PREFIX.length + 64
        private const val NAME_SEPARATOR = "--"
        private const val MAX_DISPLAY_NAME_CHARACTERS = 80
        private const val MAX_FONT_BYTES = 16L * 1_024L * 1_024L
        private const val MIN_FONT_BYTES = 12L
        private const val COPY_BUFFER_BYTES = 16 * 1_024
        private const val JOURNAL_FILE_NAME = ".restore-pending"
    }
}

/** Fixed-grid rendering requires representative ASCII glyphs to share one advance. */
internal fun isMonospaceTerminalTypeface(typeface: Typeface): Boolean {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        textSize = MONOSPACE_VALIDATION_TEXT_SIZE_PX
    }
    val reference = paint.measureText("0")
    if (!reference.isFinite() || reference <= 0f) return false
    val tolerance = maxOf(MONOSPACE_VALIDATION_MIN_TOLERANCE_PX, reference * 0.02f)
    return MONOSPACE_VALIDATION_GLYPHS.all { glyph ->
        kotlin.math.abs(paint.measureText(glyph) - reference) <= tolerance
    }
}

private const val MONOSPACE_VALIDATION_TEXT_SIZE_PX = 64f
private const val MONOSPACE_VALIDATION_MIN_TOLERANCE_PX = 0.5f
private val MONOSPACE_VALIDATION_GLYPHS = arrayOf("i", "W", "m", "@", " ")
