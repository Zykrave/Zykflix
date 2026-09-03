package com.zykrave.zykflix.models

import com.zykrave.zykflix.adapters.AppAdapter

sealed interface Show : AppAdapter.Item {
    var isFavorite: Boolean
}
