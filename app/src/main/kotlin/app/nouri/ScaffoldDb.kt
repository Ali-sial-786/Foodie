package app.nouri

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scaffold")
data class ScaffoldEntity(@PrimaryKey val id: Long, val name: String)

@Dao
interface ScaffoldDao {
    @Query("SELECT * FROM scaffold")
    fun all(): Flow<List<ScaffoldEntity>>
}

@Database(entities = [ScaffoldEntity::class], version = 1, exportSchema = false)
abstract class ScaffoldDb : RoomDatabase() {
    abstract fun dao(): ScaffoldDao
}
