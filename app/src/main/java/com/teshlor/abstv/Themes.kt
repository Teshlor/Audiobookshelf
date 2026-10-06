package com.teshlor.abstv

import androidx.annotation.DrawableRes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.sin

// Colour tokens, glows and motifs per theme. Source of truth: design/themes/HANDOFF.md.
// Glow units are dp, measured from the TOP-END corner of the screen.

data class AbsColors(
    val bg: Color, val surface: Color, val onSurface: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val focusBorder: Color,
    val wash: Color, val washAlpha: Float, val error: Color = Color(0xFFF87171),
    val layers: List<ArtLayer> = emptyList(), val glows: List<Glow> = emptyList(),
)

/** Soft light: a radial gradient that is solid out to [core] (fraction of the radius) then fades out. */
data class Glow(val dxFromEnd: Float, val dy: Float, val radius: Float, val color: Color, val alpha: Float, val core: Float = 0f)

/** How big an art layer is: fixed dp, or fractions of the screen (1f = full width/height). */
sealed interface LayerSize {
    data class Fixed(val width: Dp, val height: Dp) : LayerSize
    data class Fraction(val width: Float, val height: Float) : LayerSize
}

/**
 * One decorative image behind the content (vector or WebP/PNG). Layers draw in list order, after the
 * wash and glows. [onAllScreens] = false shows it on Home and Login only; true also on Detail and Player.
 */
data class ArtLayer(
    @DrawableRes val res: Int,
    val alignment: Alignment = Alignment.TopEnd,
    val size: LayerSize,
    val onAllScreens: Boolean = false,
    val contentScale: ContentScale = ContentScale.FillBounds,
)

/** The current 380x150dp top-end corner vectors (placeholders until the richer art lands). */
fun cornerMotif(@DrawableRes res: Int) = ArtLayer(res, Alignment.TopEnd, LayerSize.Fixed(380.dp, 150.dp))

val LocalAbsColors = staticCompositionLocalOf { AbsPalettes.getValue(AbsTheme.AUTUMN) }

val AbsPalettes: Map<AbsTheme, AbsColors> = mapOf(
    AbsTheme.AUTUMN to AbsColors(
        bg = Color(0xFF1E1914), surface = Color(0xFF473A30), onSurface = Color(0xFFF3EAE1), muted = Color(0xFFB9AA9C),
        accent = Color(0xFFF59E0B), onAccent = Color(0xFF1E1305), focusBorder = Color(0xFFF59E0B),
        wash = Color(0xFFB5561A), washAlpha = 0.19f,
        layers = listOf(cornerMotif(R.drawable.motif_autumn)),
        glows = listOf(
            Glow(70.0f, 15.0f, 190.0f, Color(0xFFF59E0B), 0.10f),
        ),
    ),
    AbsTheme.HALLOWEEN to AbsColors(
        bg = Color(0xFF140F1B), surface = Color(0xFF3E3049), onSurface = Color(0xFFEFE8F6), muted = Color(0xFFB3A6C4),
        accent = Color(0xFFFF922E), onAccent = Color(0xFF1F0E00), focusBorder = Color(0xFFFF922E),
        wash = Color(0xFF5B33A6), washAlpha = 0.24f,
        layers = listOf(cornerMotif(R.drawable.motif_halloween)),
        glows = listOf(
            Glow(80.0f, 52.5f, 150.0f, Color(0xFFE2D6F5), 0.13f),
        ),
    ),
    AbsTheme.THANKSGIVING to AbsColors(
        bg = Color(0xFF1E1215), surface = Color(0xFF4B3237), onSurface = Color(0xFFF6EAE3), muted = Color(0xFFC4ABA7),
        accent = Color(0xFFEBC873), onAccent = Color(0xFF241A05), focusBorder = Color(0xFFEBC873),
        wash = Color(0xFF7E2236), washAlpha = 0.26f,
        layers = listOf(cornerMotif(R.drawable.motif_thanksgiving)),
        glows = listOf(
            Glow(100.0f, 30.0f, 180.0f, Color(0xFFF2B35A), 0.11f),
        ),
    ),
    AbsTheme.CHRISTMAS to AbsColors(
        bg = Color(0xFF17120F), surface = Color(0xFF383B33), onSurface = Color(0xFFF6EDE3), muted = Color(0xFFC0B1A2),
        accent = Color(0xFFA8D4A0), onAccent = Color(0xFF0F1F10), focusBorder = Color(0xFFA8D4A0),
        wash = Color(0xFFB4441C), washAlpha = 0.19f,
        glows = listOf(
            Glow(-10.0f, -10.0f, 280.0f, Color(0xFFD9531F), 0.22f),
            Glow(265.0f, 18.0f, 24.2f, Color(0xFFFFCF7A), 0.50f, 0.35f),
            Glow(238.0f, 32.8f, 17.6f, Color(0xFFFBE6C2), 0.50f, 0.35f),
            Glow(211.0f, 38.6f, 17.6f, Color(0xFFFFB066), 0.50f, 0.35f),
            Glow(184.0f, 50.3f, 24.2f, Color(0xFFD0606A), 0.42f, 0.35f),
            Glow(157.0f, 51.3f, 17.6f, Color(0xFFFFCF7A), 0.50f, 0.35f),
            Glow(130.0f, 57.0f, 17.6f, Color(0xFF6FA37E), 0.42f, 0.35f),
            Glow(103.0f, 51.3f, 24.2f, Color(0xFFFBE6C2), 0.50f, 0.35f),
            Glow(76.0f, 50.3f, 17.6f, Color(0xFFFFB066), 0.50f, 0.35f),
            Glow(49.0f, 38.6f, 17.6f, Color(0xFFFFCF7A), 0.50f, 0.35f),
            Glow(22.0f, 32.8f, 24.2f, Color(0xFFD0606A), 0.42f, 0.35f),
            Glow(-5.0f, 18.0f, 17.6f, Color(0xFFFBE6C2), 0.50f, 0.35f),
            Glow(120.0f, 105.0f, 27.0f, Color(0xFFFFCF7A), 0.10f, 0.55f),
            Glow(35.0f, 95.0f, 20.0f, Color(0xFFFBE6C2), 0.09f, 0.55f),
            Glow(200.0f, 115.0f, 23.0f, Color(0xFFFFB066), 0.08f, 0.55f),
        ),
    ),
    AbsTheme.WINTER to AbsColors(
        bg = Color(0xFF111721), surface = Color(0xFF323E50), onSurface = Color(0xFFE8EEF6), muted = Color(0xFFA3B1C4),
        accent = Color(0xFFA9CBF5), onAccent = Color(0xFF0C1624), focusBorder = Color(0xFFA9CBF5),
        wash = Color(0xFF3466A8), washAlpha = 0.21f,
        layers = listOf(cornerMotif(R.drawable.motif_winter)),
        glows = listOf(
            Glow(80.0f, -5.0f, 215.0f, Color(0xFF7FB0EA), 0.12f),
        ),
    ),
    AbsTheme.SPRING to AbsColors(
        bg = Color(0xFF131A16), surface = Color(0xFF33453A), onSurface = Color(0xFFEAF3EC), muted = Color(0xFFA8BBAE),
        accent = Color(0xFFF6AACA), onAccent = Color(0xFF2A0E1B), focusBorder = Color(0xFFF6AACA),
        wash = Color(0xFF3B8A55), washAlpha = 0.19f,
        layers = listOf(cornerMotif(R.drawable.motif_spring)),
        glows = listOf(
            Glow(60.0f, 15.0f, 180.0f, Color(0xFFF6AACA), 0.09f),
        ),
    ),
    AbsTheme.SUMMER to AbsColors(
        bg = Color(0xFF0E191D), surface = Color(0xFF2A454C), onSurface = Color(0xFFE5F4F3), muted = Color(0xFF9BBCBD),
        accent = Color(0xFF52D9C5), onAccent = Color(0xFF04211D), focusBorder = Color(0xFF52D9C5),
        wash = Color(0xFF1C6C78), washAlpha = 0.20f,
        layers = listOf(cornerMotif(R.drawable.motif_summer)),
        glows = listOf(
            Glow(80.0f, 93.0f, 170.0f, Color(0xFFFFB45E), 0.26f),
        ),
    ),
    AbsTheme.NEW_YEAR to AbsColors(
        bg = Color(0xFF0E1121), surface = Color(0xFF323854), onSurface = Color(0xFFEEF0F8), muted = Color(0xFFAEB3CC),
        accent = Color(0xFFECD9A4), onAccent = Color(0xFF221B07), focusBorder = Color(0xFFECD9A4),
        wash = Color(0xFF353C94), washAlpha = 0.22f,
        layers = listOf(cornerMotif(R.drawable.motif_newyear)),
        glows = listOf(
            Glow(80.0f, 30.0f, 180.0f, Color(0xFFECD9A4), 0.09f),
        ),
    ),
    AbsTheme.AURORA to AbsColors(
        bg = Color(0xFF0B1317), surface = Color(0xFF2A4148), onSurface = Color(0xFFE6F3F1), muted = Color(0xFF9FBAB8),
        accent = Color(0xFF80EBC2), onAccent = Color(0xFF052219), focusBorder = Color(0xFF80EBC2),
        wash = Color(0xFF1F7A63), washAlpha = 0.19f,
        layers = listOf(cornerMotif(R.drawable.motif_aurora)),  // + aurora ribbons, drawn in ThemeDecor
        glows = emptyList(),
    ),
    AbsTheme.VALENTINE to AbsColors(
        bg = Color(0xFF1B0F15), surface = Color(0xFF4A2F3C), onSurface = Color(0xFFF7E9EF), muted = Color(0xFFC7A9B6),
        accent = Color(0xFFFFA3B8), onAccent = Color(0xFF2C0A16), focusBorder = Color(0xFFFFA3B8),
        wash = Color(0xFF8A2A4E), washAlpha = 0.22f,
        layers = listOf(cornerMotif(R.drawable.motif_valentine)),
        glows = listOf(
            Glow(70.0f, 30.0f, 170.0f, Color(0xFFFF8FA8), 0.11f),
        ),
    ),
    AbsTheme.FIREFLIES to AbsColors(
        bg = Color(0xFF0E1611), surface = Color(0xFF30443A), onSurface = Color(0xFFECF4E8), muted = Color(0xFFA9BBA8),
        accent = Color(0xFFD8F07C), onAccent = Color(0xFF1A2204), focusBorder = Color(0xFFD8F07C),
        wash = Color(0xFF2B5E3B), washAlpha = 0.21f,
        layers = listOf(cornerMotif(R.drawable.motif_fireflies)),
        glows = listOf(
            Glow(100.0f, 75.0f, 190.0f, Color(0xFF2F6B3E), 0.10f),
            Glow(60.0f, 35.0f, 13.0f, Color(0xFFD8F07C), 0.38f),
            Glow(120.0f, 70.0f, 10.4f, Color(0xFFD8F07C), 0.38f),
            Glow(20.0f, 95.0f, 11.7f, Color(0xFFD8F07C), 0.38f),
            Glow(180.0f, 45.0f, 9.1f, Color(0xFFD8F07C), 0.38f),
            Glow(80.0f, 115.0f, 9.8f, Color(0xFFD8F07C), 0.38f),
            Glow(215.0f, 95.0f, 7.8f, Color(0xFFD8F07C), 0.38f),
            Glow(145.0f, 125.0f, 7.2f, Color(0xFFD8F07C), 0.38f),
            Glow(255.0f, 35.0f, 6.5f, Color(0xFFD8F07C), 0.38f),
            Glow(35.0f, 60.0f, 6.5f, Color(0xFFD8F07C), 0.38f),
            Glow(100.0f, 20.0f, 7.8f, Color(0xFFD8F07C), 0.38f),
            Glow(300.0f, 80.0f, 5.9f, Color(0xFFD8F07C), 0.38f),
            Glow(0.0f, 130.0f, 7.8f, Color(0xFFD8F07C), 0.38f),
        ),
    ),
    AbsTheme.JULY_4 to AbsColors(
        bg = Color(0xFF0D1223), surface = Color(0xFF313B5A), onSurface = Color(0xFFEDF0F9), muted = Color(0xFFA9B2CF),
        accent = Color(0xFFB3CDFF), onAccent = Color(0xFF0B1530), focusBorder = Color(0xFFB3CDFF),
        wash = Color(0xFF2B3F86), washAlpha = 0.21f,
        layers = listOf(cornerMotif(R.drawable.motif_july4)),
        glows = listOf(
            Glow(70.0f, 50.0f, 55.9f, Color(0xFFF2F4FF), 0.09f),
            Glow(165.0f, 84.0f, 37.7f, Color(0xFFFF8080), 0.09f),
            Glow(17.0f, 111.0f, 31.2f, Color(0xFF86AEFF), 0.10f),
        ),
    ),
)

/**
 * Static decor behind a screen: top wash, glows and (when [full]) the corner motif. Drawn once per
 * size/theme in drawWithCache; nothing animates. Detail and Player pass full = false (wash only).
 */
@Composable
fun ThemeDecor(theme: AbsTheme, full: Boolean) {
    val c = AbsPalettes.getValue(theme)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val wash = Brush.verticalGradient(
                    0f to c.wash.copy(alpha = c.washAlpha),
                    0.5f to c.wash.copy(alpha = 0f),
                    endY = size.height,
                )
                val glows = if (full) c.glows.map { g ->
                    val center = Offset(size.width - g.dxFromEnd.dp.toPx(), g.dy.dp.toPx())
                    val r = g.radius.dp.toPx()
                    Triple(
                        Brush.radialGradient(
                            0f to g.color.copy(alpha = g.alpha),
                            g.core.coerceIn(0f, 0.99f) to g.color.copy(alpha = g.alpha),
                            1f to g.color.copy(alpha = 0f),
                            center = center, radius = r,
                        ),
                        center, r,
                    )
                } else emptyList()
                onDrawBehind {
                    drawRect(wash)
                    glows.forEach { (brush, center, r) -> drawCircle(brush, r, center) }
                    if (full && theme == AbsTheme.AURORA) drawAurora()
                }
            },
        )
        c.layers.filter { full || it.onAllScreens }.forEach { l ->
            val sized = when (val z = l.size) {
                is LayerSize.Fixed -> Modifier.size(z.width, z.height)
                is LayerSize.Fraction -> Modifier.fillMaxWidth(z.width).fillMaxHeight(z.height)
            }
            Image(
                painterResource(l.res), contentDescription = null,
                modifier = Modifier.align(l.alignment).then(sized),
                contentScale = l.contentScale,
            )
        }
    }
}

private class Ribbon(
    val top: (Float) -> Float, val bottom: (Float) -> Float,
    val from: Int, val to: Int, val color: Color, val peak: Float,
)

private val ribbons = listOf(
    Ribbon({ 30 + 26 * sin(it / 150) }, { 150 + 34 * sin(it / 120 + 1.1f) }, -500, 800, Color(0xFF3DDC97), 0.40f),
    Ribbon({ 90 + 22 * sin(it / 130 + 2.0f) }, { 200 + 26 * sin(it / 110 + 0.3f) }, -200, 800, Color(0xFF8A6CE0), 0.26f),
)

/** Two aurora ribbons in motif-viewport px (1px = 0.5dp, x = 0 is 380dp from the right edge), fading in from the left. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAurora() {
    val unit = 0.5.dp.toPx()
    val originX = size.width - 380.dp.toPx()
    drawIntoCanvas { canvas ->
        ribbons.forEach { r ->
            val xs = (r.from..r.to step 10).map { it.toFloat() }
            val path = Path().apply {
                xs.forEachIndexed { i, x -> if (i == 0) moveTo(originX + x * unit, r.top(x) * unit) else lineTo(originX + x * unit, r.top(x) * unit) }
                xs.reversed().forEach { x -> lineTo(originX + x * unit, r.bottom(x) * unit) }
                close()
            }
            val b = path.getBounds()
            val x0 = originX + r.from * unit
            val x1 = originX + r.to * unit
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
            drawPath(
                path,
                Brush.verticalGradient(
                    0f to r.color.copy(alpha = 0f),
                    0.78f to r.color.copy(alpha = r.peak),
                    1f to r.color.copy(alpha = 0.04f),
                    startY = b.top, endY = b.bottom,
                ),
            )
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent, 0.5f to Color.Black, 1f to Color.Black,
                    startX = x0, endX = x1,
                ),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }
}
