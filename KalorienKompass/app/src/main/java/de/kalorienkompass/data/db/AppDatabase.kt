package de.kalorienkompass.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        FoodEntity::class,
        DiaryEntryEntity::class,
        MeasurementEntity::class,
        DailyActivityEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao
    abstract fun diaryDao(): DiaryDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun activityDao(): ActivityDao
    abstract fun recipeDao(): RecipeDao

    companion object {
        const val NAME = "kalorienkompass.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .build()
    }
}
