package com.localdoc.ai.sync

/** Облако в памяти с возможностью обрыва связи и вмешательства другого устройства. */
class MemoryCloud : SyncTransport {
    var file: RemoteFile? = null
    private var counter = 0
    var failDownload = false
    var failUpload = false
    var uploads = 0

    /** Вызывается перед записью: имитирует, что другое устройство успело записать файл раньше. */
    var beforeUpload: (suspend () -> Unit)? = null

    override suspend fun download(): RemoteFile? {
        if (failDownload) throw SyncTransportException("Нет связи с сервером")
        return file
    }

    override suspend fun upload(bytes: ByteArray, previousVersion: String?): String {
        beforeUpload?.let { hook -> beforeUpload = null; hook() }
        if (failUpload) throw SyncTransportException("Связь оборвалась при отправке")
        val current = file
        if (previousVersion == null && current != null) throw UploadConflictException()
        if (!previousVersion.isNullOrEmpty() && current?.version != previousVersion) throw UploadConflictException()
        uploads++
        val version = "v${++counter}"
        file = RemoteFile(bytes, version)
        return version
    }
}

/** Вид данных в памяти. [unappliable] — ключи, которые пока нельзя применить (нет книги). */
class MapCollection(override val kind: String, override val tracksDeletion: Boolean = true) : SyncCollection {
    val data = LinkedHashMap<String, String>()
    val unappliable = HashSet<String>()
    var failApply = false
    override fun read(): Map<String, String> = data.toMap()
    override fun apply(upserts: Map<String, String>, removals: Set<String>): Set<String> {
        if (failApply) throw IllegalStateException("диск полон")
        removals.forEach { data.remove(it) }
        val pending = HashSet<String>()
        for ((k, v) in upserts) if (k in unappliable) pending += k else data[k] = v
        return pending
    }
}

/** Одно устройство: свои данные, своё состояние синхронизации и часы. */
class Device(val cloud: SyncTransport, var now: Long, val cipher: SyncCipher = FastCipher("пароль")) {
    val progress = MapCollection("progress", tracksDeletion = false)
    val marks = MapCollection("bookmark")
    var state: String? = null
    var copies = 0
    val applied = ArrayList<Set<String>>()
    val engine = SyncEngine(listOf(progress, marks), { state }, { state = it }, { now }, { copies++ }, { applied += it })
    suspend fun sync(choice: DivergenceChoice? = null) = engine.sync(cloud, cipher, choice)
}

fun FastCipher(password: String) = PasswordCipher(password, iterations = 1_000)

const val T0 = 1_700_000_000_000L
