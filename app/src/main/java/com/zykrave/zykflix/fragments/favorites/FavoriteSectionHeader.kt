package com.zykrave.zykflix.fragments.favorites

import com.zykrave.zykflix.adapters.AppAdapter

data class FavoriteSectionHeader(
    val title: String,
    val section: FavoritesViewModel.Section,
) : AppAdapter.Item {
    override var itemType: AppAdapter.Type = AppAdapter.Type.FAVORITE_SECTION_HEADER
}
