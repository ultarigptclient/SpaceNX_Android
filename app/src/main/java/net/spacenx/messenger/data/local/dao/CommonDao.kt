package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.CommonEntity

@Dao
interface CommonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CommonEntity)

    @Query("SELECT * FROM config WHERE `key` = :key")
    suspend fun getByKey(key: String): CommonEntity?

    @Query("SELECT value FROM config WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("DELETE FROM config WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM config")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSync(entity: CommonEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CommonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(entities: List<CommonEntity>)

    @Query("SELECT * FROM config")
    suspend fun getAll(): List<CommonEntity>
}
