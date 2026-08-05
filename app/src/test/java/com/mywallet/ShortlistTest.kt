package com.mywallet

import com.mywallet.domain.Shortlist
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order a row of chips offers its answers in.
 *
 * Four rows on the entry form share it, so what is pinned here is the rule
 * rather than any one row: what the user actually uses leads, what the form
 * opened on leads that, and nothing moves under the thumb afterwards.
 */
class ShortlistTest {

    private data class Chip(val id: String)

    private fun order(ids: List<String>, ranking: List<String>, selected: String? = null) =
        Shortlist.order(ids.map { Chip(it) }, ranking, selected) { it.id }.map { it.id }

    @Test
    fun `the most used comes first`() {
        assertEquals(
            listOf("bank", "cash", "wallet"),
            order(listOf("wallet", "cash", "bank"), listOf("bank", "cash", "wallet")),
        )
    }

    @Test
    fun `what the form opened on leads whatever else happens`() {
        // Reopening an entry filed against something rarely touched must still
        // show its own answer. Past the end of the shortlist it could not.
        assertEquals(
            listOf("wallet", "bank", "cash"),
            order(listOf("bank", "cash", "wallet"), listOf("bank", "cash", "wallet"), "wallet"),
        )
    }

    @Test
    fun `a holding the ranking has never met is offered rather than dropped`() {
        // An account created since the ranking was read. It goes after
        // everything ranked — not in front of the account the rent has been
        // paid from for two years — but it is still on the list.
        assertEquals(
            listOf("bank", "cash", "brand-new"),
            order(listOf("brand-new", "cash", "bank"), listOf("bank", "cash")),
        )
    }

    @Test
    fun `with no ranking at all the list is left as it arrived`() {
        // A phone with no history. The accounts list is already in the order the
        // user created them, and reordering it by nothing would be arbitrary.
        assertEquals(
            listOf("cash", "wallet", "bank"),
            order(listOf("cash", "wallet", "bank"), emptyList()),
        )
    }

}
