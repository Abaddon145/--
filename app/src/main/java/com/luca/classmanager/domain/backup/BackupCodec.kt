package com.luca.classmanager.domain.backup

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON backup. [BackupEnvelope.checksum] is the SHA-256 of the exact payload string.
 * A mismatch, unknown version, or broken reference is rejected before any database write.
 */
object BackupCodec {
    const val FORMAT = "luca-interest-classroom"
    const val VERSION = 1
    const val SCHEMA_VERSION = 2

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(bundle: BackupBundle): String {
        val payload = json.encodeToString(BackupBundle.serializer(), bundle)
        val envelope = BackupEnvelope(
            format = FORMAT,
            version = VERSION,
            checksum = sha256(payload),
            payload = payload,
        )
        return json.encodeToString(BackupEnvelope.serializer(), envelope)
    }

    fun decode(text: String): BackupDecode {
        val envelope = try {
            json.decodeFromString(BackupEnvelope.serializer(), text)
        } catch (_: Exception) {
            return BackupDecode.Rejected("备份文件无法读取")
        }
        if (envelope.format != FORMAT) return BackupDecode.Rejected("这不是 Luca 兴趣课堂的备份")
        if (envelope.version != VERSION) return BackupDecode.Rejected("不支持这个备份版本")
        if (envelope.checksum != sha256(envelope.payload)) return BackupDecode.Rejected("备份校验没有通过，没有改动现有数据")
        val bundle = try {
            json.decodeFromString(BackupBundle.serializer(), envelope.payload)
        } catch (_: Exception) {
            return BackupDecode.Rejected("备份内容不完整")
        }
        return validate(bundle)?.let { BackupDecode.Rejected(it) } ?: BackupDecode.Ready(bundle)
    }

    /**
     * Failed checks keep the current snapshot. Only a ready backup replaces it.
     */
    fun apply(current: BackupBundle, decoded: BackupDecode): BackupBundle {
        return if (decoded is BackupDecode.Ready) decoded.bundle else current
    }

    private fun validate(bundle: BackupBundle): String? {
        if (bundle.schemaVersion != SCHEMA_VERSION) return "备份的数据库版本无法恢复"
        if (duplicate(bundle.courses.map { it.id })) return "备份里有重复的课程"
        if (duplicate(bundle.rules.map { it.id })) return "备份里有重复的上课规则"
        if (duplicate(bundle.events.map { it.id })) return "备份里有重复的课程安排"
        if (duplicate(bundle.attendance.map { it.id })) return "备份里有重复的打卡"
        if (duplicate(bundle.packages.map { it.id })) return "备份里有重复的课时套餐"
        if (duplicate(bundle.payments.map { it.id })) return "备份里有重复的缴费"
        if (duplicate(bundle.adjustments.map { it.id })) return "备份里有重复的课时调整"
        if (duplicate(bundle.historical.map { it.id })) return "备份里有重复的历史记录"
        if (duplicate(bundle.reminders.map { it.id })) return "备份里有重复的提醒"
        if (duplicate(bundle.attachments.map { it.id })) return "备份里有重复的附件"
        val courses = bundle.courses.map { it.id }.toSet()
        val missingCourse = bundle.rules.any { it.courseId !in courses } ||
            bundle.events.any { it.courseId !in courses } ||
            bundle.attendance.any { it.courseId !in courses } ||
            bundle.packages.any { it.courseId !in courses } ||
            bundle.payments.any { it.courseId !in courses } ||
            bundle.adjustments.any { it.courseId !in courses } ||
            bundle.historical.any { it.courseId !in courses } ||
            bundle.reminders.any { it.courseId != null && it.courseId !in courses }
        if (missingCourse) return "备份里有找不到课程的记录"
        val packageIds = bundle.packages.map { it.id }.toSet()
        if (bundle.payments.any { it.packageId != null && it.packageId !in packageIds }) {
            return "备份里有找不到套餐的缴费"
        }
        val fileNames = bundle.files.map { it.fileName }.toSet()
        if (fileNames.size != bundle.files.size) return "备份里有重复的照片文件"
        if (bundle.files.any { !safeName(it.fileName) || runCatching { Base64.getDecoder().decode(it.base64) }.isFailure }) {
            return "备份里的照片文件无法读取"
        }
        if (bundle.attachments.any { it.fileName != null && (it.fileName !in fileNames || !safeName(it.fileName)) }) {
            return "备份里的照片和记录对不上"
        }
        return null
    }

    private fun duplicate(ids: List<String>): Boolean = ids.size != ids.toSet().size

    private fun safeName(name: String): Boolean {
        if (name.isBlank() || name.length > 120 || name.contains("..")) return false
        return name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

@Serializable
data class BackupEnvelope(
    val format: String,
    val version: Int,
    val checksum: String,
    val payload: String,
)
