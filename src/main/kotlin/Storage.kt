package chattore

import chattore.commands.MailboxItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object About : Table("about") {
    val uuid = varchar("about_uuid", 36).index()
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
    val uuid = varchar("nick_uuid", 36).index()
    val nick = varchar("nick_nick", 2048)
    override val primaryKey = PrimaryKey(uuid)
}

object UsernameCache : Table("username_cache") {
    val uuid = varchar("cache_user", 36).index()
    val username = varchar("cache_username", 16).index()
    override val primaryKey = PrimaryKey(uuid)
}

object Setting : Table("setting") {
    val uuid = varchar("setting_uuid", 36).index()
    val key = varchar("setting_key", 16).index()
    val value = blob("setting_value")
}

fun serializeSetting(value: JsonElement) : ExposedBlob =
    ExposedBlob(Json.encodeToString(JsonElement.serializer(), value).toByteArray())

fun deserializeSetting(blob: ExposedBlob) : JsonElement =
    Json.decodeFromString(blob.bytes.decodeToString())

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
        SchemaUtils.create(About, Mail, Nick, UsernameCache, Setting)
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

    fun setSetting(uuid: UUID, key: String, element: JsonElement) = transaction(database) {
        Setting.upsert {
            it[this.uuid] = uuid.toString()
            it[this.key] = key
            it[this.value] = serializeSetting(element)
        }
    }

    fun unsetSetting(uuid: UUID, key: String) = transaction(database) {
        Setting.deleteWhere { (Setting.uuid eq uuid.toString()) and (Setting.key eq key) }
    }

    fun getSettings(uuid: UUID) : Map<String, JsonElement> = transaction(database) {
        Setting.selectAll().where { Setting.uuid eq uuid.toString() }.associate {
            it[Setting.key] to deserializeSetting(it[Setting.value])
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