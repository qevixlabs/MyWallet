package com.mywallet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.dao.FxRateDao
import com.mywallet.data.db.dao.LoanDao
import com.mywallet.data.db.dao.RateChangeDao
import com.mywallet.data.db.dao.RecurringSeriesDao
import com.mywallet.data.db.dao.MoneyEntryDao
import com.mywallet.data.db.entity.AccountEntity
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.FxRateEntity
import com.mywallet.data.db.entity.EntryStatus
import com.mywallet.data.db.entity.LoanEntity
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.data.db.entity.LoanDirection
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.db.entity.LoanPart
import com.mywallet.data.db.entity.RateChangeEntity
import com.mywallet.data.db.entity.RecurrenceInterval
import com.mywallet.data.db.entity.RecurringSeriesEntity
import com.mywallet.data.db.entity.MoneyEntryEntity

/**
 * Enums are stored as their names rather than ordinals: reordering the enum
 * later must not silently reinterpret every existing row.
 */
class Converters {
    @TypeConverter fun directionToString(value: Direction): String = value.name

    @TypeConverter
    fun stringToDirection(value: String): Direction =
        runCatching { Direction.valueOf(value) }.getOrDefault(Direction.OUT)

    @TypeConverter fun accountKindToString(value: AccountKind): String = value.name

    @TypeConverter
    fun stringToAccountKind(value: String): AccountKind =
        runCatching { AccountKind.valueOf(value) }.getOrDefault(AccountKind.SAVINGS)

    @TypeConverter fun loanPartToString(value: LoanPart?): String? = value?.name

    /**
     * Null is the common case — most entries are not a one-sided loan payment —
     * so this converter is nullable both ways rather than inventing a value.
     */
    @TypeConverter
    fun stringToLoanPart(value: String?): LoanPart? =
        value?.let { runCatching { LoanPart.valueOf(it) }.getOrNull() }

    @TypeConverter fun intervalToString(value: RecurrenceInterval): String = value.name

    @TypeConverter
    fun stringToInterval(value: String): RecurrenceInterval =
        runCatching { RecurrenceInterval.valueOf(value) }.getOrDefault(RecurrenceInterval.MONTHLY)

    @TypeConverter fun statusToString(value: EntryStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): EntryStatus =
        runCatching { EntryStatus.valueOf(value) }.getOrDefault(EntryStatus.CONFIRMED)

    @TypeConverter fun loanKindToString(value: LoanKind): String = value.name

    @TypeConverter
    fun stringToLoanKind(value: String): LoanKind =
        runCatching { LoanKind.valueOf(value) }.getOrDefault(LoanKind.BANK)

    @TypeConverter fun loanDirectionToString(value: LoanDirection): String = value.name

    @TypeConverter
    fun stringToLoanDirection(value: String): LoanDirection =
        runCatching { LoanDirection.valueOf(value) }.getOrDefault(LoanDirection.BORROWED)

    @TypeConverter fun instalmentStyleToString(value: InstalmentStyle): String = value.name

    @TypeConverter
    fun stringToInstalmentStyle(value: String): InstalmentStyle =
        runCatching { InstalmentStyle.valueOf(value) }.getOrDefault(InstalmentStyle.LEVEL_EMI)

}

@Database(
    entities = [
        MoneyEntryEntity::class,
        AccountEntity::class,
        FxRateEntity::class,
        RecurringSeriesEntity::class,
        LoanEntity::class,
        RateChangeEntity::class,
    ],
    version = 31,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MyWalletDatabase : RoomDatabase() {
    abstract fun moneyEntryDao(): MoneyEntryDao
    abstract fun accountDao(): AccountDao
    abstract fun fxRateDao(): FxRateDao
    abstract fun recurringSeriesDao(): RecurringSeriesDao
    abstract fun loanDao(): LoanDao
    abstract fun rateChangeDao(): RateChangeDao

    companion object {
        const val NAME = "mywallet.db"
    }
}
