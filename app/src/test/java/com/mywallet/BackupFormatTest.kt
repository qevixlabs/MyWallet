package com.mywallet

import com.mywallet.data.backup.BackupAccount
import com.mywallet.data.backup.BackupEntry
import com.mywallet.data.backup.BackupLoan
import com.mywallet.data.backup.BackupRateChange
import com.mywallet.data.backup.BackupRule
import com.mywallet.data.db.entity.AccountEntity
import com.mywallet.data.db.entity.LoanEntity
import com.mywallet.data.db.entity.MoneyEntryEntity
import com.mywallet.data.db.entity.RateChangeEntity
import com.mywallet.data.db.entity.RecurringSeriesEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Every column a table has must have somewhere to go in a backup.
 *
 * This is the test the format needed and did not have. A backup is JSON written
 * by hand-maintained mappers, so a column added to an entity is silently absent
 * from the file until somebody remembers the other half of the change — and the
 * failure is invisible until a user restores onto a new phone and finds their
 * fixed deposit has no term, their policy nothing to pay out, and their loans
 * gone entirely. Every one of those was true at once.
 *
 * Names only, and deliberately: a backup stores an enum as a string and a
 * `Direction` as `"IN"`, so the types differ by design while the set of facts
 * must not. Comparing the sets in both directions also catches the opposite
 * mistake — a field left in the file after the column behind it was dropped.
 *
 * If this fails, the fix is almost never to edit the expectation: it is to carry
 * the new column through `toBackup` and `toEntity`. The one honest reason to
 * exclude something would be a value derived from other columns, and there is
 * nothing like that in these tables.
 */
class BackupFormatTest {

    @Test
    fun `an entry is backed up in full`() =
        assertSameFields(MoneyEntryEntity::class.java, BackupEntry::class.java)

    @Test
    fun `a holding is backed up in full`() {
        // The one that went wrong: a deposit's term, a policy's premium and
        // maturity, a goal's target and the rule that pays into it were all
        // columns on this table and none of them were in the file.
        assertSameFields(AccountEntity::class.java, BackupAccount::class.java)
    }

    @Test
    fun `a debt is backed up in full`() {
        // `principalMinor`, `startedOn` and `carriedInterestMinor` are the ones to
        // watch: a lump sum rewrites all three in place, so none of them can be
        // worked out again from anything else in the file.
        assertSameFields(LoanEntity::class.java, BackupLoan::class.java)
    }

    @Test
    fun `a repeating rule is backed up in full`() {
        // Including `materialisedThrough`, which is the boundary between the
        // occurrences that already exist as rows and the ones still to come. A
        // rule restored without it writes a second copy of everything it has
        // ever paid.
        assertSameFields(RecurringSeriesEntity::class.java, BackupRule::class.java)
    }

    @Test
    fun `a rate move is backed up in full`() =
        assertSameFields(RateChangeEntity::class.java, BackupRateChange::class.java)

    /**
     * Java reflection rather than Kotlin's, so the test needs no reflect
     * dependency: a data class's backing fields carry exactly its property names.
     * Static and compiler-generated fields are dropped — `@Serializable` leaves a
     * `Companion` behind and Compose leaves a `$stable`.
     */
    private fun assertSameFields(entity: Class<*>, backup: Class<*>) {
        assertEquals(
            "${entity.simpleName} and ${backup.simpleName} describe different facts",
            entity.fieldNames(),
            backup.fieldNames(),
        )
    }

    private fun Class<*>.fieldNames(): Set<String> =
        declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) || it.name.contains('$') }
            .map { it.name }
            .toSortedSet()
}
