package app.donodavez.core.data

import app.donodavez.core.net.DvJson
import kotlinx.serialization.KSerializer

/** Cache de leitura tipado sobre a tabela de snapshots. */
class Snapshots(private val dao: SnapshotDao) {
    suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        dao.put(SnapshotEntity(key, DvJson.encodeToString(serializer, value), System.currentTimeMillis()))
    }

    suspend fun <T> load(key: String, serializer: KSerializer<T>): Pair<T, Long>? {
        val row = dao.get(key) ?: return null
        return runCatching { DvJson.decodeFromString(serializer, row.json) to row.savedAt }.getOrNull()
    }
}
