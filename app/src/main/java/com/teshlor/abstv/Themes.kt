package com.teshlor.abstv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap

// Colour tokens and the header band per theme. Source of truth: design/themes/HANDOFF.md (v2).

data class AbsColors(
    val bg: Color, val surface: Color, val onSurface: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val focusBorder: Color,
    val error: Color = Color(0xFFF87171),
    /** Opaque 1920x480 header image; its last rows equal [bg], so nothing is drawn below it. */
    @DrawableRes val band: Int,
)

val LocalAbsColors = staticCompositionLocalOf { AbsPalettes.getValue(AbsTheme.AUTUMN) }

val AbsPalettes: Map<AbsTheme, AbsColors> = mapOf(
    AbsTheme.AUTUMN to AbsColors(  // Autumn: Golden hour on a maple ridge
        bg = Color(0xFF1E1914), surface = Color(0xFF473A30), onSurface = Color(0xFFF3EAE1), muted = Color(0xFFB9AA9C),
        accent = Color(0xFFF59E0B), onAccent = Color(0xFF1E1305), focusBorder = Color(0xFFF59E0B),
        band = R.drawable.band_autumn,
    ),
    AbsTheme.HALLOWEEN to AbsColors(  // Halloween: Moonrise, a crooked house, two jack-o'-lanterns
        bg = Color(0xFF140F1B), surface = Color(0xFF3E3049), onSurface = Color(0xFFEFE8F6), muted = Color(0xFFB3A6C4),
        accent = Color(0xFFFF922E), onAccent = Color(0xFF1F0E00), focusBorder = Color(0xFFFF922E),
        band = R.drawable.band_halloween,
    ),
    AbsTheme.THANKSGIVING to AbsColors(  // Thanksgiving: Candlelit harvest table: wheat, gourds, oak leaves
        bg = Color(0xFF1E1215), surface = Color(0xFF4B3237), onSurface = Color(0xFFF6EAE3), muted = Color(0xFFC4ABA7),
        accent = Color(0xFFEBC873), onAccent = Color(0xFF241A05), focusBorder = Color(0xFFEBC873),
        band = R.drawable.band_thanksgiving,
    ),
    AbsTheme.CHRISTMAS to AbsColors(  // Christmas: Fireside garland, warm lights, glass baubles
        bg = Color(0xFF17120F), surface = Color(0xFF383B33), onSurface = Color(0xFFF6EDE3), muted = Color(0xFFC0B1A2),
        accent = Color(0xFFA8D4A0), onAccent = Color(0xFF0F1F10), focusBorder = Color(0xFFA8D4A0),
        band = R.drawable.band_christmas,
    ),
    AbsTheme.NEW_YEAR to AbsColors(  // New Year's Eve: Champagne fireworks over a midnight city
        bg = Color(0xFF0E1121), surface = Color(0xFF323854), onSurface = Color(0xFFEEF0F8), muted = Color(0xFFAEB3CC),
        accent = Color(0xFFECD9A4), onAccent = Color(0xFF221B07), focusBorder = Color(0xFFECD9A4),
        band = R.drawable.band_newyear,
    ),
    AbsTheme.WINTER to AbsColors(  // Winter: Snowy pines at blue hour
        bg = Color(0xFF111721), surface = Color(0xFF323E50), onSurface = Color(0xFFE8EEF6), muted = Color(0xFFA3B1C4),
        accent = Color(0xFFA9CBF5), onAccent = Color(0xFF0C1624), focusBorder = Color(0xFFA9CBF5),
        band = R.drawable.band_winter,
    ),
    AbsTheme.AURORA to AbsColors(  // Northern Lights: Aurora over a snowbound forest
        bg = Color(0xFF0B1317), surface = Color(0xFF2A4148), onSurface = Color(0xFFE6F3F1), muted = Color(0xFF9FBAB8),
        accent = Color(0xFF80EBC2), onAccent = Color(0xFF052219), focusBorder = Color(0xFF80EBC2),
        band = R.drawable.band_aurora,
    ),
    AbsTheme.VALENTINE to AbsColors(  // Valentine's: Candlelit garden roses
        bg = Color(0xFF1B0F15), surface = Color(0xFF4A2F3C), onSurface = Color(0xFFF7E9EF), muted = Color(0xFFC7A9B6),
        accent = Color(0xFFFFA3B8), onAccent = Color(0xFF2C0A16), focusBorder = Color(0xFFFFA3B8),
        band = R.drawable.band_valentine,
    ),
    AbsTheme.SPRING to AbsColors(  // Spring: Hanami at dusk: sakura, ink-wash hills, lantern light
        bg = Color(0xFF15141F), surface = Color(0xFF3D3650), onSurface = Color(0xFFF4ECF2), muted = Color(0xFFB9ADC0),
        accent = Color(0xFFF7B8CF), onAccent = Color(0xFF2B0F1D), focusBorder = Color(0xFFF7B8CF),
        band = R.drawable.band_spring,
    ),
    AbsTheme.SUMMER to AbsColors(  // Summer: Sunset over the sea, palm fronds overhead
        bg = Color(0xFF0E191D), surface = Color(0xFF2A454C), onSurface = Color(0xFFE5F4F3), muted = Color(0xFF9BBCBD),
        accent = Color(0xFF52D9C5), onAccent = Color(0xFF04211D), focusBorder = Color(0xFF52D9C5),
        band = R.drawable.band_summer,
    ),
    AbsTheme.FIREFLIES to AbsColors(  // Firefly Nights: June dusk, fireflies over the meadow
        bg = Color(0xFF0E1611), surface = Color(0xFF30443A), onSurface = Color(0xFFECF4E8), muted = Color(0xFFA9BBA8),
        accent = Color(0xFFD8F07C), onAccent = Color(0xFF1A2204), focusBorder = Color(0xFFD8F07C),
        band = R.drawable.band_fireflies,
    ),
    AbsTheme.JULY_4 to AbsColors(  // Fourth of July: Fireworks over the lake
        bg = Color(0xFF0D1223), surface = Color(0xFF313B5A), onSurface = Color(0xFFEDF0F9), muted = Color(0xFFA9B2CF),
        accent = Color(0xFFB3CDFF), onAccent = Color(0xFF0B1530), focusBorder = Color(0xFFB3CDFF),
        band = R.drawable.band_july4,
    ),
)

/**
 * Optional header band (Home and Login) behind the content. One opaque image in its own graphics
 * layer: it is recorded once and does not redraw as focus moves or the list scrolls. The Surface
 * already paints the bg colour; Detail and Player pass showBand = false and get bg only.
 */
@Composable
fun ThemeDecor(theme: AbsTheme, showBand: Boolean) {
    // Decoded unconditionally (and remembered per theme) so re-entering Home never re-decodes on the main thread.
    val band = ImageBitmap.imageResource(AbsPalettes.getValue(theme).band)
    if (!showBand) return
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = band,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxWidth().aspectRatio(4f).graphicsLayer(),
        )
    }
}
