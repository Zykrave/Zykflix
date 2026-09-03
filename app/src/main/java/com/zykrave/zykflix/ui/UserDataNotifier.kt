package com.zykrave.zykflix.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce

object UserDataNotifier {
    private val _updates = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )

    val updates = _updates
        .debounce(300)

    fun notifyChanged() {
        _updates.tryEmit(Unit)
    }
}