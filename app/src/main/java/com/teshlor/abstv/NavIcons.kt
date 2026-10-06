package com.teshlor.abstv

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Rail icons. Home/Search/Settings/Logout/Check come from material-icons-core (no extended-icons dependency);
// Library, Series and Collections are simple 24dp paths drawn here, filled when selected and outlined otherwise.

private fun shape(name: String, d: String, filled: Boolean): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString(d).toNodes(),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
        )
    }.build()

private const val LIBRARY = "M3,5.5 C5.5,4.5 9,4.5 11.5,6 L11.5,19.5 C9,18.2 5.5,18.2 3,19.2 Z M20.5,5.5 C18,4.5 14.5,4.5 12.5,6 L12.5,19.5 C14.5,18.2 18,18.2 20.5,19.2 Z"
private const val SERIES = "M4,3.5 H20 V7.5 H4 Z M4,10 H20 V14 H4 Z M4,16.5 H20 V20.5 H4 Z"
private const val COLLECTIONS = "M7,3.5 H19 V17 H7 Z M3.5,7 H5 V20.5 H15.5 V19 H3.5 Z M10,3.5 V10 L13,8 L16,10 V3.5 Z"

object NavIcons {
    private val library = lazy { shape("library", LIBRARY, false) to shape("libraryFilled", LIBRARY, true) }
    private val series = lazy { shape("series", SERIES, false) to shape("seriesFilled", SERIES, true) }
    private val collections = lazy { shape("collections", COLLECTIONS, false) to shape("collectionsFilled", COLLECTIONS, true) }

    fun forTab(tab: Tab, selected: Boolean): ImageVector = when (tab) {
        Tab.SEARCH -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
        Tab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
        Tab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
        Tab.LIBRARY -> library.value.let { if (selected) it.second else it.first }
        Tab.SERIES -> series.value.let { if (selected) it.second else it.first }
        Tab.COLLECTIONS -> collections.value.let { if (selected) it.second else it.first }
    }

    val logout: ImageVector get() = Icons.AutoMirrored.Outlined.ExitToApp
    val check: ImageVector get() = Icons.Filled.Check
}
