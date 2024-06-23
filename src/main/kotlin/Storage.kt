package chattore

import chattore.commands.MailboxItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.reflect.KClass

object About : Table("about") {
    val uuid = varchar("about_uuid", 36).uniqueIndex()
    val about = varchar("about_about", 512)
    override val primaryKey = PrimaryKey(uuid)
}

object Mail : Table("mail") {
    val id = integer("mail_id").autoIncrement()
    val timestamp = integer("mail_timestamp")
    val sender = varchar("mail_sender", 36).index()
    val recipient = varchar("mail_recipient", 36).index()
    val read = bool("mail_read").default(false)
    val message = varchar("mail_message", 512)
    override val primaryKey = PrimaryKey(id)
}

object Nick : Table("nick") {
    val uuid = varchar("nick_uuid", 36).uniqueIndex()
    val nick = varchar("nick_nick", 2048)
    override val primaryKey = PrimaryKey(uuid)
}

object UsernameCache : Table("username_cache") {
    val uuid = varchar("cache_user", 36).uniqueIndex()
    val username = varchar("cache_username", 16).index()
    override val primaryKey = PrimaryKey(uuid)
}

object SettingTable : Table("setting") {
    val uuid = varchar("setting_uuid", 36).index()
    val key = varchar("setting_key", 16).index()
    val value = blob("setting_value")
    init {
        SettingTable.uniqueIndex(uuid, key)
    }
}

@Serializable
sealed class Setting

@Serializable
data class SpySetting(val enabled: Boolean) : Setting() {
    companion object {
        const val KEY = "spy"
    }
}

val jsonHelper = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

fun <T : Setting> keyForSetting(clazz: KClass<T>) : String {
    return when(clazz) {
        SpySetting::class -> SpySetting.KEY
        else -> throw IllegalArgumentException("Unsupported setting type")
    }
}

inline fun <reified T : Setting> blobToSetting(blob: ByteArray): T? {
    val jsonString = String(blob)
    return when (T::class) {
        SpySetting::class -> jsonHelper.decodeFromString<SpySetting>(jsonString) as? T
        else -> throw IllegalArgumentException("Unsupported setting type")
    }
}

inline fun <reified T : Setting> settingToBlob(setting: T): ByteArray {
    val jsonString = jsonHelper.encodeToString(setting)
    return jsonString.toByteArray()
}

class Storage(
    dbFile: String
) {
    var uuidToUsernameCache = mapOf<UUID, String>()
    var usernameToUuidCache = mapOf<String, UUID>()
    private val database = Database.connect("jdbc:sqlite:${dbFile}", "org.sqlite.JDBC")

    init {
        initTables()
    }

    private fun initTables() = transaction(database) {
        SchemaUtils.create(About, Mail, Nick, UsernameCache, SettingTable)
    }

    fun setAbout(uuid: UUID, about: String) = transaction(database) {
        About.upsert {
            it[this.uuid] = uuid.toString()
            it[this.about] = about
        }
    }

    fun getAbout(uuid: UUID) : String? = transaction(database) {
        About.selectAll().where { About.uuid eq uuid.toString() }.firstOrNull()?.let { it[About.about] }
    }

    fun unsetSetting(uuid: UUID, key: String) = transaction(database) {
        SettingTable.deleteWhere { (SettingTable.uuid eq uuid.toString()) and (SettingTable.key eq key) }
    }

    fun getRawSetting(uuid: UUID, key: String): ByteArray? = transaction(database) {
        SettingTable.select(SettingTable.value).where {
            (SettingTable.uuid eq uuid.toString()) and (SettingTable.key eq key)
        }.singleOrNull()?.get(SettingTable.value)?.bytes
    }

    inline fun <reified T : Setting> getSetting(uuid: UUID): T? {
        val key = keyForSetting(T::class)
        val blob = getRawSetting(uuid, key)
        return blob?.let { blobToSetting<T>(it) }
    }

    fun setSetting(uuid: UUID, setting: Setting) {
        val key = keyForSetting(setting::class)
        val blob = settingToBlob(setting)
        transaction(database) {
            SettingTable.upsert {
                it[this.uuid] = uuid.toString()
                it[this.key] = key
                it[this.value] = ExposedBlob(blob)
            }
        }
    }

    fun removeNickname(target: UUID) = transaction(database) {
        Nick.deleteWhere { Nick.uuid eq target.toString() }
    }

    fun getNickname(target: UUID): String? = transaction(database) {
        Nick.selectAll().where { Nick.uuid eq target.toString() }.firstOrNull()?.let { it[Nick.nick] }
    }

    fun setNickname(target: UUID, nickname: String) = transaction(database) {
        Nick.upsert {
            it[this.uuid] = target.toString()
            it[this.nick] = nickname
        }
    }

    fun ensureCachedUsername(user: UUID, username: String) = transaction(database) {
        UsernameCache.upsert {
            it[this.uuid] = user.toString()
            it[this.username] = username
        }
        updateLocalUsernameCache()
    }

    fun updateLocalUsernameCache() {
        uuidToUsernameCache = transaction(database) {
            UsernameCache.selectAll().associate {
                UUID.fromString(it[UsernameCache.uuid]) to it[UsernameCache.username]
            }
        }
        usernameToUuidCache = uuidToUsernameCache.entries.associate{(k,v)-> v to k}
    }

    fun insertMessage(sender: UUID, recipient: UUID, message: String) = transaction(database) {
        Mail.insert {
            it[this.timestamp] = System.currentTimeMillis().floorDiv(1000).toInt()
            it[this.sender] = sender.toString()
            it[this.recipient] = recipient.toString()
            it[this.message] = message
        }
    }

    fun readMessage(recipient: UUID, id: Int): Pair<UUID, String>? = transaction(database) {
        Mail.selectAll().where { (Mail.id eq id) and (Mail.recipient eq recipient.toString()) }
            .firstOrNull()?.let { toReturn ->
            markRead(id, true)
            UUID.fromString(toReturn[Mail.sender]) to toReturn[Mail.message]
        }
    }

    fun getMessages(recipient: UUID): List<MailboxItem> = transaction(database) {
        Mail.selectAll().where { Mail.recipient eq recipient.toString() }
            .orderBy(Mail.timestamp to SortOrder.DESC) .map {
            MailboxItem(
                it[Mail.id],
                it[Mail.timestamp],
                UUID.fromString(it[Mail.sender]),
                it[Mail.read]
            )
        }
    }

    private fun markRead(id: Int, read: Boolean) = transaction(database) {
        Mail.update({Mail.id eq id}) {
            it[this.read] = read
        }
    }
}