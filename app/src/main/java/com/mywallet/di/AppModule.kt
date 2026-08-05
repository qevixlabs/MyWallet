package com.mywallet.di

import android.content.Context
import androidx.room.Room
import com.mywallet.data.db.MyWalletDatabase
import com.mywallet.data.db.Migrations
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.dao.FxRateDao
import com.mywallet.data.db.dao.LoanDao
import com.mywallet.data.db.dao.RateChangeDao
import com.mywallet.data.db.dao.MoneyEntryDao
import com.mywallet.data.db.dao.RecurringSeriesDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MyWalletDatabase =
        Room.databaseBuilder(context, MyWalletDatabase::class.java, MyWalletDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            // No fallbackToDestructiveMigration: this is the user's only copy of
            // their financial history. A missing migration must fail loudly in
            // development, never wipe data on someone's phone.
            .build()


    @Provides fun provideMoneyEntryDao(db: MyWalletDatabase): MoneyEntryDao = db.moneyEntryDao()

    @Provides fun provideAccountDao(db: MyWalletDatabase): AccountDao = db.accountDao()

    @Provides fun provideFxRateDao(db: MyWalletDatabase): FxRateDao = db.fxRateDao()

    @Provides
    fun provideRecurringSeriesDao(db: MyWalletDatabase): RecurringSeriesDao =
        db.recurringSeriesDao()

    @Provides fun provideLoanDao(db: MyWalletDatabase): LoanDao = db.loanDao()

    @Provides
    fun provideRateChangeDao(db: MyWalletDatabase): RateChangeDao = db.rateChangeDao()

    @Provides @IoDispatcher fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Lives as long as the process. For work that must finish even if the screen
     * that started it goes away — seeding labels, writing a backup.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
