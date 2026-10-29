package com.geriatricare.app.di

import android.content.Context
import androidx.room.Room
import com.geriatricare.app.data.db.AppDatabase
import com.geriatricare.app.data.db.VitalReadingDao
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "geriatricare_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides @Singleton
    fun provideVitalDao(db: AppDatabase): VitalReadingDao = db.vitalReadingDao()
}
