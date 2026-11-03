package app.donodavez.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** Último mapa baixado (somente leitura offline; ações sempre reconsultam o servidor). */
@Entity(tableName = "map_features")
data class MapFeatureEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
    val featureJson: String,
    val fetchedAt: Long,
)

/** Últimas telas de leitura (carteira, imóveis, propostas) para exibição sem rede. */
@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey val key: String,
    val json: String,
    val savedAt: Long,
)

@Dao
interface MapFeatureDao {
    @Upsert
    suspend fun upsert(items: List<MapFeatureEntity>)

    @Query("SELECT * FROM map_features WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon LIMIT :limit")
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int = 500): List<MapFeatureEntity>

    @Query("DELETE FROM map_features WHERE fetchedAt < :before")
    suspend fun purgeOlderThan(before: Long)

    @Query("DELETE FROM map_features")
    suspend fun clear()
}

@Dao
interface SnapshotDao {
    @Upsert
    suspend fun put(item: SnapshotEntity)

    @Query("SELECT * FROM snapshots WHERE `key` = :key")
    suspend fun get(key: String): SnapshotEntity?

    @Query("DELETE FROM snapshots")
    suspend fun clear()
}

@Database(entities = [MapFeatureEntity::class, SnapshotEntity::class], version = 1, exportSchema = false)
abstract class DvDatabase : RoomDatabase() {
    abstract fun features(): MapFeatureDao
    abstract fun snapshots(): SnapshotDao
}
