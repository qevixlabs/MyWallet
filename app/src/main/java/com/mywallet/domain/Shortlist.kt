package com.mywallet.domain

/**
 * The order a row of chips is offered in, and how many of them are offered
 * before the rest are asked for.
 *
 * Both ends of the entry form ask which account with more answers than fit on a
 * phone, and both answer it the way `CurrencyOption.shortlist` does: a few in
 * front, most likely first, and the rest behind one word. Written once here
 * rather than twice in the view model, because two rows quietly disagreeing
 * about what "most likely" means is how a form stops reading as one form.
 *
 * What "most likely" means is [ranking] — the ids of everything the user has,
 * most-used first, worked out from the movements they have actually recorded
 * rather than from a tally of what they have tapped. See `HoldingUseRow`.
 */
object Shortlist {

    /** The accounts money moves between. Two lines of chips. */
    const val HOLDINGS = 8

    /**
     * How much use one holding has had.
     *
     * [uses] leads and [lastOn] only breaks its ties: a salary account touched
     * once a month must not fall behind a cash tin because the tin was touched
     * yesterday.
     */
    data class Use(val id: String, val uses: Int, val lastOn: Long)

    /**
     * [items] reordered so the likeliest answers come first.
     *
     * [selected] leads whatever else happens: a row of choices that hides the
     * one it is currently on cannot show its own answer, and the form opens with
     * an answer already filled in. Then [ranking], then anything the ranking has
     * never heard of — a holding created since it was worked out — in the order
     * it arrived, so a new account is offered rather than silently dropped past
     * the end of the shortlist.
     *
     * Worked out when the form loads and deliberately not again as the user
     * taps. A list that reordered itself under the thumb would move the chip
     * beside the one being aimed at.
     */
    fun <T> order(
        items: List<T>,
        ranking: List<String>,
        selected: String? = null,
        id: (T) -> String,
    ): List<T> {
        // Rank by position, and put the unranked *after* everything ranked
        // rather than at position -1, which would promote a brand-new holding
        // above the account the user has paid their rent from for two years.
        val places = ranking.withIndex().associate { (place, key) -> key to place }
        val unranked = ranking.size
        return items.sortedWith(
            compareBy(
                { if (selected != null && id(it) == selected) 0 else 1 },
                { places[id(it)] ?: unranked },
            )
        )
    }
}
