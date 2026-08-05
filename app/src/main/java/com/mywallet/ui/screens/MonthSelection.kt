package com.mywallet.ui.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which month the two month-shaped pages are looking at, as an offset from this
 * one — 0 is now, −1 is last month.
 *
 * Home and the Timeline are two views of one month: what happened in it, and
 * what it does to every balance. They each kept their own offset, so "See all"
 * dropped the reader out of June and into today with no way back but counting
 * the steps again — and switching tabs at any other time did the same.
 *
 * Deliberately not persisted. A month is where the user happens to be looking
 * this session, not a setting; an app reopened a week later should open on the
 * month it is, not on the one they were reading when they closed it.
 */
@Singleton
class MonthSelection @Inject constructor() {

    private val _offset = MutableStateFlow(0)
    val offset: StateFlow<Int> = _offset.asStateFlow()

    fun show(offset: Int) { _offset.value = offset }
}
