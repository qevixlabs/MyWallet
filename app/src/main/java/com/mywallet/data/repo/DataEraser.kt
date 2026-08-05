package com.mywallet.data.repo

import com.mywallet.data.db.MyWalletDatabase
import com.mywallet.data.settings.SettingsStore
import com.mywallet.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Throws away every figure the app holds and leaves a usable empty app behind.
 *
 * This is the only destructive operation in MyWallet, and it is deliberately
 * total: entries, accounts, loans, repeating rules and cached rates all go.
 * Tombstones go with them — a soft delete exists so a *restore* cannot resurrect
 * something the user deleted, which is the opposite of what is wanted here.
 * "Start over" that leaves 400 tombstones behind is not starting over.
 *
 * **The preferences go too.** They used to survive, on the reasoning that
 * somebody clearing their figures does not want to be asked which calendar they
 * read again — but every one of them was chosen *for* those figures, and what
 * start over means is a phone that has never been opened: the app comes back on
 * the welcome questions, and offers again the two things it offers a new user.
 * That includes the lock, which is off afterwards; a cleared app guarding
 * nothing is not what the switch was turned on for.
 *
 * Nothing has to be done about the **interest payout interval** any more. It is a
 * fact about a bank rather than a preference, and it lives on the account it
 * describes — so clearing the tables takes it with the figures it produced, and
 * the next account asks the question again on its own.
 *
 * Starter labels come back, because an empty label list makes the app unusable —
 * money cannot be added without one.
 */
@Singleton
class DataEraser @Inject constructor(
    private val database: MyWalletDatabase,
    private val settings: SettingsStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Off the main thread, deliberately: clearing every table is a blocking
     * transaction, and Room refuses to run one on the UI thread rather than let
     * it freeze the screen.
     */
    suspend fun eraseEverything() = withContext(io) {
        database.clearAllTables()
        // The preferences go too: they were all chosen *for* those figures, so
        // the app comes back on the welcome questions rather than on somebody
        // else's currency and calendar.
        settings.clearAll()
    }
}
