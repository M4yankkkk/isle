package dev.isle.isle

import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Accent = Color(0xFFBDF5CE)
private val Muted = Color(0xFF9B9B9B)

@Composable
fun PillOverlay(host: IslandService) {
    val settings = host.settings
    val density = LocalDensity.current.density
    val suppressed = host.state.suppressed
    val visible = !suppressed && settings.alwaysVisible
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val pillW = settings.width * density
            val pillH = settings.height * density
            if (visible) {
                drawRoundRect(Color.Black, Offset(0f, 0f), Size(pillW, pillH), CornerRadius(pillH / 2))
                if (settings.outline && host.state.presentation != Presentation.EXPANDED) {
                    drawRect(Color(0xFF57D6AC), style = androidx.compose.ui.graphics.drawscope.Stroke(density))
                    drawRoundRect(Color(0xFFFF594F), Offset(0f, 0f), Size(pillW, pillH), CornerRadius(pillH / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(density))
                }
                // A small static activity dot avoids covering the physical camera.
                if (host.state.presentation == Presentation.COMPACT && settings.width >= 60) {
                    drawCircle(
                        if (host.charging && settings.charging) Accent else Color.White,
                        2.5f * density,
                        Offset(pillW - 12 * density, pillH / 2)
                    )
                }
            }
        }
    }
}

@Composable
fun PanelOverlay(host: IslandService) {
    val settings = host.settings
    val expanded = host.state.presentation == Presentation.EXPANDED
    val suppressed = host.state.suppressed
    val morph = remember { Animatable(0f) }
    val contentAlpha = remember { Animatable(0f) }
    val density = LocalDensity.current.density

    LaunchedEffect(expanded, suppressed) {
        if (suppressed) {
            contentAlpha.snapTo(0f)
            morph.snapTo(0f)
            host.animationFinished()
        } else if (expanded) {
            launch {
                delay(85)
                contentAlpha.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
            }
            morph.animateTo(
                1f,
                spring(dampingRatio = 0.72f, stiffness = 220f)
            )
        } else {
            launch {
                contentAlpha.animateTo(0f, tween(70))
            }
            morph.animateTo(
                0f,
                spring(dampingRatio = 0.85f, stiffness = 340f)
            )
            host.animationFinished()
        }
    }

    val isPillMode = settings.alwaysVisible
    val startW = if (isPillMode) settings.width.toFloat() else settings.height.toFloat()
    val startH = settings.height.toFloat()
    val panelW = host.panelWidthDp
    val panelTop = host.panelTopDp

    var showDashboardOverride by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded) showDashboardOverride = false
    }

    val interactive = expanded && !suppressed
    val isNotificationMode = host.peek != null
    val isMediaMode = host.media != null && !host.chargePeek && !isNotificationMode && !showDashboardOverride

    // Screen-specific target heights:
    // - Media Player: 184dp (compact Apple Dynamic Island style)
    // - Notification: 224dp
    // - Dashboard / Homescreen: 220dp (generous, uncongested breathing room)
    val targetCardHeight = when {
        isNotificationMode -> 224.dp
        isMediaMode -> 184.dp
        else -> 220.dp
    }

    // Dynamic height morph animation when switching screens with Apple-like spring curve
    val animatedCardHeight by animateDpAsState(
        targetValue = targetCardHeight,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 280f
        ),
        label = "islandCardHeight"
    )

    val selectedFont = remember(settings.fontStyle) {
        resolveFontFamily(settings.fontStyle)
    }

    val isYouTube = isYouTubePackage(host.media?.controller?.packageName)

    val albumPalette = remember(host.media?.artwork, isYouTube) {
        if (isYouTube) YouTubePalette else extractAlbumPalette(host.media?.artwork)
    }

    val useAlbumColor = settings.glassmorphism && (settings.dynamicAlbumColor || isYouTube) && isMediaMode && albumPalette.hasColor

    val targetTopColor = if (useAlbumColor) albumPalette.topTint else Color(0xFA121217)
    val targetBottomColor = if (useAlbumColor) albumPalette.bottomTint else Color(0xFD09090C)

    val animTopColor by animateColorAsState(
        targetValue = targetTopColor,
        animationSpec = tween(durationMillis = 300),
        label = "glassTopColor"
    )
    val animBottomColor by animateColorAsState(
        targetValue = targetBottomColor,
        animationSpec = tween(durationMillis = 300),
        label = "glassBottomColor"
    )

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Color.Black)) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = selectedFont)) {
            Box(Modifier.fillMaxSize()) {
                val clipPath = remember { Path() }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop.dp)
                        .width(panelW.dp)
                        .height(animatedCardHeight)
                        .drawWithContent {
                            val raw = morph.value
                            val s = raw.coerceAtLeast(0f)
                            if (s < 0.005f) return@drawWithContent

                            // Width is strictly clamped so it never exceeds panelW or cuts off on sides
                            val px = if (isPillMode) {
                                if (s <= 1f) s.pow(1.15f) else 1f
                            } else {
                                if (s <= 1f) s.pow(0.96f) else 1f
                            }.coerceIn(0f, 1f)

                            val py = if (isPillMode) {
                                if (s <= 1f) s.pow(0.85f) else s
                            } else {
                                if (s <= 1f) s.pow(0.88f) else s
                            }

                            val pr = if (isPillMode) {
                                if (s <= 1f) s.pow(1.4f) else 1f
                            } else {
                                if (s <= 1f) s.pow(1.3f) else 1f
                            }

                            val startW_px = startW * density
                            val startH_px = startH * density
                            val panelW_px = size.width
                            val panelH_px = size.height

                            val curW = (startW_px + (panelW_px - startW_px) * px).coerceIn(startW_px, panelW_px)
                            val curH = (startH_px + (panelH_px - startH_px) * py).coerceAtLeast(startH_px)
                            val curTop = (py - 1f) * panelTop * density
                            val curLeft = (size.width - curW) / 2f

                            // Corner radius:
                            // In transparent/no-pill mode: startW == startH == 34dp.
                            // When s = 0: curW == curH == 34dp, circleRadius = 17dp -> A 100% PERFECT CIRCLE!
                            // While closing: shrinks smoothly into a true circle centered on the camera lens!
                            // When s = 1: curRadius = 28dp (squircle card).
                            val minDim = minOf(curW, curH)
                            val circleRadius = minDim / 2f
                            val cardRadius = 28f * density
                            val curRadius = circleRadius + (cardRadius - circleRadius) * pr

                            val alpha = if (s < 0.01f) 0f else if (s < 0.05f) (s - 0.01f) / 0.04f else 1f

                            clipPath.rewind()
                            clipPath.addRoundRect(
                                RoundRect(
                                    left = curLeft,
                                    top = curTop,
                                    right = curLeft + curW,
                                    bottom = curTop + curH,
                                    cornerRadius = CornerRadius(curRadius, curRadius)
                                )
                            )

                            // 1. Draw container (Obsidian Tinted Glass vs OLED Pitch Black)
                            if (settings.glassmorphism) {
                                // Deep obsidian glass base: blocks underlying clock/widgets with 96% opacity
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            animTopColor,
                                            animBottomColor
                                        ),
                                        startY = curTop,
                                        endY = curTop + curH
                                    ),
                                    topLeft = Offset(curLeft, curTop),
                                    size = Size(curW, curH),
                                    cornerRadius = CornerRadius(curRadius, curRadius),
                                    alpha = alpha
                                )

                                // Soft top specular light reflection
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.08f * alpha),
                                            Color.Transparent
                                        ),
                                        startY = curTop,
                                        endY = curTop + curH * 0.40f
                                    ),
                                    topLeft = Offset(curLeft, curTop),
                                    size = Size(curW, curH * 0.40f),
                                    cornerRadius = CornerRadius(curRadius, curRadius),
                                    alpha = alpha
                                )
                            } else {
                                drawRoundRect(
                                    color = Color.Black,
                                    topLeft = Offset(curLeft, curTop),
                                    size = Size(curW, curH),
                                    cornerRadius = CornerRadius(curRadius, curRadius),
                                    alpha = alpha
                                )
                            }

                            // 2. Draw polished glass rim highlight
                            if (s > 0.06f) {
                                if (settings.glassmorphism) {
                                    val rimAlpha = ((s - 0.06f) / 0.25f).coerceIn(0f, 1f) * 0.45f
                                    drawRoundRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = rimAlpha * alpha),
                                                Color.White.copy(alpha = rimAlpha * 0.15f * alpha),
                                                Color.White.copy(alpha = rimAlpha * 0.35f * alpha)
                                            ),
                                            startY = curTop,
                                            endY = curTop + curH
                                        ),
                                        topLeft = Offset(curLeft, curTop),
                                        size = Size(curW, curH),
                                        cornerRadius = CornerRadius(curRadius, curRadius),
                                        style = Stroke(width = 1.2f * density)
                                    )
                                } else {
                                    val borderAlpha = ((s - 0.06f) / 0.25f).coerceIn(0f, 1f) * 0.16f
                                    drawRoundRect(
                                        color = Color.White,
                                        topLeft = Offset(curLeft, curTop),
                                        size = Size(curW, curH),
                                        cornerRadius = CornerRadius(curRadius, curRadius),
                                        style = Stroke(width = density),
                                        alpha = borderAlpha * alpha
                                    )
                                }
                            }

                            // 3. Clip and draw inner composable content
                            clipPath(clipPath) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    val activeScreen = when {
                        isNotificationMode -> 0
                        isMediaMode -> 1
                        else -> 2
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = if (isNotificationMode) 20.dp else 22.dp,
                                vertical = if (isNotificationMode) 14.dp else if (isMediaMode) 18.dp else 20.dp
                            )
                            .graphicsLayer {
                                val ca = contentAlpha.value
                                this.alpha = ((ca - 0.10f) / 0.90f).coerceIn(0f, 1f)
                                val scale = 0.92f + 0.08f * ca
                                this.scaleX = scale
                                this.scaleY = scale
                                this.translationY = (1f - ca) * 6 * density
                            }
                    ) {
                        Crossfade(
                            targetState = activeScreen,
                            animationSpec = tween(durationMillis = 200),
                            label = "screenTransition"
                        ) { screen ->
                            when (screen) {
                                0 -> NotificationCardContent(host, interactive)
                                1 -> MediaCardContent(
                                    host = host,
                                    interactive = interactive,
                                    onShowDashboard = { showDashboardOverride = true }
                                )
                                else -> DashboardContent(
                                    host = host,
                                    interactive = interactive,
                                    onShowPlayer = if (host.media != null) { { showDashboardOverride = false } } else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IslandOverlay(host: IslandService) {
    PanelOverlay(host)
}

@Composable
private fun NotificationCardContent(host: IslandService, interactive: Boolean) {
    val peek = host.peek ?: return
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = peek.app.uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(
                onClick = { host.collapse() },
                enabled = interactive,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("Close", color = Muted, fontSize = 12.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(peek.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(peek.text, fontSize = 13.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (peek.intent != null) {
            TextButton(
                onClick = { host.openNotification() },
                enabled = interactive,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Open app →", color = Accent, fontSize = 13.sp)
            }
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private val NDotFont: FontFamily by lazy {
    try {
        FontFamily(Font(R.font.ndot57))
    } catch (_: Exception) {
        FontFamily.Monospace
    }
}

private val NTypeFont: FontFamily by lazy {
    try {
        FontFamily(
            Font(R.font.ntype82_regular, FontWeight.Normal),
            Font(R.font.ntype82_regular, FontWeight.Medium),
            Font(R.font.ntype82_headline, FontWeight.SemiBold),
            Font(R.font.ntype82_headline, FontWeight.Bold)
        )
    } catch (_: Exception) {
        FontFamily.Default
    }
}

private val InstrumentSansFont: FontFamily by lazy {
    try {
        FontFamily(
            Font(R.font.instrument_sans_regular, FontWeight.Normal),
            Font(R.font.instrument_sans_medium, FontWeight.Medium),
            Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
            Font(R.font.instrument_sans_semibold, FontWeight.Bold),
            Font(R.font.instrument_sans_bold, FontWeight.ExtraBold),
            Font(R.font.instrument_sans_bold, FontWeight.Black)
        )
    } catch (_: Exception) {
        FontFamily.Default
    }
}

private val InterFont: FontFamily by lazy {
    try {
        FontFamily(
            Font(R.font.inter_regular, FontWeight.Normal),
            Font(R.font.inter_medium, FontWeight.Medium),
            Font(R.font.inter_medium, FontWeight.SemiBold),
            Font(R.font.inter_medium, FontWeight.Bold)
        )
    } catch (_: Exception) {
        FontFamily.Default
    }
}

private fun resolveFontFamily(style: String): FontFamily = when (style.lowercase()) {
    "instrument" -> InstrumentSansFont
    "inter" -> InterFont
    "ndot" -> NDotFont
    "system" -> FontFamily.Default
    else -> NTypeFont
}

@Composable
private fun DashboardContent(
    host: IslandService,
    interactive: Boolean,
    onShowPlayer: (() -> Unit)? = null
) {
    val calendar = remember { Calendar.getInstance() }
    val dayName = remember { SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time) }
    val dateText = remember { SimpleDateFormat("d MMMM", Locale.getDefault()).format(calendar.time) }
    val density = LocalDensity.current.density
    val isGlass = host.settings.glassmorphism

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // --- 1. Top Section: Date & Day (Left) + Battery Gauge Pill (Right) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Date & Day
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = dayName,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFFA5A5A5)
                )
            }

            // Battery Status Pill with Circular Progress Ring & NDot Font
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (isGlass) Color(0xFF1E1E26) else Color(0xFF16161A))
                    .border(
                        if (isGlass) 1.dp else 0.6.dp,
                        if (isGlass) Color(0x38FFFFFF) else Color(0xFF26262A),
                        RoundedCornerShape(26.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Progress Gauge Ring
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val progress = (host.battery.coerceAtLeast(0) / 100f).coerceIn(0f, 1f)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeW = 2.8f * density
                        val r = (size.minDimension - strokeW) / 2f
                        // Inactive track
                        drawCircle(
                            color = Color(0xFF26262C),
                            radius = r,
                            center = center,
                            style = Stroke(width = strokeW)
                        )
                        // Active lavender arc
                        drawArc(
                            color = Color(0xFFB088FF),
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = Offset(strokeW / 2f, strokeW / 2f),
                            size = Size(size.width - strokeW, size.height - strokeW),
                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                        )
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lucide_zap),
                        contentDescription = "Charge",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // NDot Percentage + Charging Label
                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        text = if (host.battery >= 0) "${host.battery}%" else "—",
                        fontSize = 18.sp,
                        fontFamily = NDotFont,
                        color = Color.White,
                        lineHeight = 20.sp
                    )
                    Text(
                        text = if (host.charging) "Charging" else "Battery",
                        fontSize = 11.sp,
                        color = Color(0xFFA5A5A5),
                        lineHeight = 13.sp
                    )
                }
            }
        }

        // --- 2. Bottom Section: 4 Squircle Action Tiles ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Action 1: Torch / Flashlight
            val isTorchOn = host.torchOn
            DashboardActionTile(
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lucide_flashlight),
                        contentDescription = "Torch",
                        tint = if (isTorchOn) Color(0xFFB088FF) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = "Torch",
                active = isTorchOn,
                activeColor = Color(0xFF231C38),
                activeBorderColor = Color(0xFF8A6CFF),
                isGlass = isGlass,
                interactive = interactive,
                onClick = { host.toggleTorch() }
            )

            // Action 2: Ringer / Audio Profile
            val rMode = host.ringerMode
            val ringLabel = when (rMode) {
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                else -> "Ring"
            }
            val isRingerActive = rMode != AudioManager.RINGER_MODE_NORMAL
            DashboardActionTile(
                icon = {
                    val iconRes = when (rMode) {
                        AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_lucide_vibrate
                        AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_lucide_bell_off
                        else -> R.drawable.ic_lucide_bell
                    }
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = ringLabel,
                        tint = if (isRingerActive) Color(0xFFB088FF) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = ringLabel,
                active = isRingerActive,
                activeColor = Color(0xFF231C38),
                activeBorderColor = Color(0xFF8A6CFF),
                isGlass = isGlass,
                interactive = interactive,
                onClick = { host.toggleRinger() }
            )

            // Action 3: Player / Music
            val hasActiveMedia = onShowPlayer != null
            DashboardActionTile(
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lucide_music),
                        contentDescription = "Music",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = "Player",
                active = false,
                isGlass = isGlass,
                interactive = interactive,
                onClick = {
                    if (onShowPlayer != null) {
                        onShowPlayer()
                    } else {
                        host.openMusicApp()
                    }
                }
            )

            // Action 4: Clock / Timer
            DashboardActionTile(
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lucide_timer),
                        contentDescription = "Timer",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = "Timer",
                active = false,
                isGlass = isGlass,
                interactive = interactive,
                onClick = { host.openClockApp() }
            )
        }
    }
}

@Composable
private fun DashboardActionTile(
    icon: @Composable () -> Unit,
    label: String,
    active: Boolean = false,
    activeColor: Color = Color(0xFF231C38),
    activeBorderColor: Color = Color(0xFF8A6CFF),
    isGlass: Boolean = false,
    interactive: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        active -> if (isGlass) Color(0xFF2E204E) else activeColor
        isGlass -> Color(0xFF1E1E26)
        else -> Color(0xFF16161A)
    }
    val borderCol = when {
        active -> if (isGlass) Color(0xFFA78BFA) else activeBorderColor
        isGlass -> Color(0x30FFFFFF)
        else -> Color(0xFF242428)
    }
    val borderW = if (active || isGlass) 1.2.dp else 0.6.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(bg)
                .border(borderW, borderCol, RoundedCornerShape(22.dp))
                .clickable(enabled = interactive, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (active) Color.White else Color(0xFFA0A0A0),
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun MediaCardContent(
    host: IslandService,
    interactive: Boolean,
    onShowDashboard: () -> Unit = {}
) {
    val media = host.media ?: return

    var currentPos by remember(media.position, media.lastPositionUpdate, media.playing) {
        mutableLongStateOf(media.position)
    }

    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(media.playing, media.position, media.lastPositionUpdate) {
        if (media.playing && media.duration > 0L) {
            while (isActive) {
                withFrameMillis {
                    val elapsed = SystemClock.elapsedRealtime() - media.lastPositionUpdate
                    val est = (media.position + (elapsed * media.playbackSpeed).toLong()).coerceIn(0L, media.duration)
                    currentPos = est
                }
            }
        } else {
            currentPos = media.position
        }
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    // Elastic spring seek catchup
    var lastKnownPos by remember { mutableLongStateOf(media.position) }
    LaunchedEffect(media.position) {
        if (!isScrubbing && media.duration > 0L && abs(media.position - lastKnownPos) > 1200L) {
            val target = (media.position.toFloat() / media.duration).coerceIn(0f, 1f)
            animProgress.animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 300f)
            )
        }
        lastKnownPos = media.position
    }

    val displayPos = if (isScrubbing && media.duration > 0L) {
        (scrubFraction * media.duration).toLong()
    } else {
        currentPos
    }

    val currentFraction = when {
        isScrubbing -> scrubFraction
        animProgress.isRunning -> animProgress.value
        media.duration > 0L -> (currentPos.toFloat() / media.duration).coerceIn(0f, 1f)
        else -> 0f
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isScrubbing) 8.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 380f),
        label = "trackHeight"
    )

    val thumbScale by animateFloatAsState(
        targetValue = if (isScrubbing) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 420f),
        label = "thumbScale"
    )

    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "playheadPulse"
    )
    val pulseRadius by pulseAnim.animateFloat(
        initialValue = 6f,
        targetValue = 11f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "playheadRadius"
    )

    val isYouTube = isYouTubePackage(media.controller.packageName)
    val albumPalette = remember(media.artwork, isYouTube) {
        if (isYouTube) YouTubePalette else extractAlbumPalette(media.artwork)
    }
    val haloColor = if (albumPalette.hasColor) albumPalette.accentColor else Color(0x30FFFFFF)
    val targetWaveColor = if ((host.settings.dynamicAlbumColor || isYouTube) && albumPalette.hasColor) {
        albumPalette.accentColor
    } else {
        Color.White.copy(alpha = 0.88f)
    }
    val animWaveColor by animateColorAsState(
        targetValue = targetWaveColor,
        animationSpec = tween(durationMillis = 280),
        label = "waveformColor"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // --- 1. Top Section: Album Artwork + Title & Artist + Animated Waveform ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album Artwork with soft Ambient Glow
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clickable(
                        enabled = interactive,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        host.openMediaApp()
                    },
                contentAlignment = Alignment.Center
            ) {
                // Soft Ambient Halo
                Canvas(modifier = Modifier.size(72.dp)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                haloColor.copy(alpha = 0.65f),
                                haloColor.copy(alpha = 0.28f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.minDimension / 2f
                        )
                    )
                }

                // Square Artwork
                val art = media.artwork
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = "Cover Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1E1E22)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_lucide_music),
                            contentDescription = "Cover",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Track Title & Artist
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
                    .clickable(
                        enabled = interactive,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        host.openMediaApp()
                    },
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = media.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = media.artist,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFFA5A5A5),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Equalizer + Quick Dashboard Switch (Right side)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WaveformEqualizer(
                    playing = media.playing,
                    color = animWaveColor
                )

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (host.settings.glassmorphism) Color(0x33FFFFFF) else Color(0xFF1E1E22))
                        .clickable(enabled = interactive, onClick = onShowDashboard),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lucide_layout_grid),
                        contentDescription = "Dashboard",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // --- 2. Middle Section: Elapsed Time, Sleek Progress Bar, Total Duration ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatMs(displayPos),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF8E8E93),
                style = androidx.compose.ui.text.TextStyle(
                    fontFeatureSettings = "tnum"
                )
            )

            val coroutineScope = rememberCoroutineScope()
            // Sleek pill-shaped progress seekbar
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .height(28.dp)
                    .pointerInput(interactive, media.duration) {
                        if (!interactive || media.duration <= 0L) return@pointerInput
                        detectTapGestures(
                            onPress = { offset ->
                                isScrubbing = true
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                coroutineScope.launch {
                                    animProgress.snapTo(scrubFraction)
                                }
                                val targetMs = (scrubFraction * media.duration).toLong()
                                tryAwaitRelease()
                                isScrubbing = false
                                host.mediaSeek(targetMs)
                            }
                        )
                    }
                    .pointerInput(interactive, media.duration) {
                        if (!interactive || media.duration <= 0L) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isScrubbing = true
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                coroutineScope.launch {
                                    animProgress.snapTo(scrubFraction)
                                }
                            },
                            onDragEnd = {
                                isScrubbing = false
                                val targetMs = (scrubFraction * media.duration).toLong()
                                host.mediaSeek(targetMs)
                            },
                            onDragCancel = {
                                isScrubbing = false
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                coroutineScope.launch {
                                    animProgress.snapTo(scrubFraction)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current.density
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val centerY = h / 2f
                    val currentTrackH = trackHeight.toPx()
                    val trackRadius = currentTrackH / 2f
                    val currentFrac = currentFraction.coerceIn(0f, 1f)
                    val progressW = w * currentFrac

                    // 1. Inactive Background Track
                    drawRoundRect(
                        color = Color(0x38FFFFFF),
                        topLeft = Offset(0f, centerY - currentTrackH / 2f),
                        size = Size(w, currentTrackH),
                        cornerRadius = CornerRadius(trackRadius, trackRadius)
                    )

                    // 2. Active Progress Bar
                    if (progressW > 0f) {
                        val activeBrush = if ((host.settings.dynamicAlbumColor || isYouTube) && albumPalette.hasColor) {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.92f),
                                    Color.White,
                                    albumPalette.accentColor
                                ),
                                startX = 0f,
                                endX = progressW
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.88f),
                                    Color.White
                                ),
                                startX = 0f,
                                endX = progressW
                            )
                        }

                        drawRoundRect(
                            brush = activeBrush,
                            topLeft = Offset(0f, centerY - currentTrackH / 2f),
                            size = Size(progressW, currentTrackH),
                            cornerRadius = CornerRadius(trackRadius, trackRadius)
                        )

                        // 3. Glowing Leading Playhead (Breathing Glow Cap)
                        if (media.playing && !isScrubbing && progressW > 4f) {
                            val headColor = if ((host.settings.dynamicAlbumColor || isYouTube) && albumPalette.hasColor) {
                                albumPalette.accentColor
                            } else {
                                Color.White
                            }
                            val glowR = pulseRadius * density
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        headColor.copy(alpha = pulseAlpha * 0.70f),
                                        headColor.copy(alpha = pulseAlpha * 0.25f),
                                        Color.Transparent
                                    ),
                                    center = Offset(progressW, centerY),
                                    radius = glowR
                                ),
                                center = Offset(progressW, centerY),
                                radius = glowR
                            )
                            // Playhead core dot
                            drawCircle(
                                color = Color.White,
                                radius = currentTrackH / 2f,
                                center = Offset(progressW, centerY)
                            )
                        }
                    }

                    // 4. Interactive Tactile Scrubber Thumb (Expands on touch)
                    if (thumbScale > 0.01f) {
                        val thumbR = (6.5f * density) * thumbScale
                        val thumbGlowR = thumbR * 2.2f
                        val thumbCenter = Offset(progressW.coerceIn(thumbR, w - thumbR), centerY)
                        val accent = if (albumPalette.hasColor) albumPalette.accentColor else Color.White

                        // Outer soft glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.50f * thumbScale),
                                    accent.copy(alpha = 0.15f * thumbScale),
                                    Color.Transparent
                                ),
                                center = thumbCenter,
                                radius = thumbGlowR
                            ),
                            center = thumbCenter,
                            radius = thumbGlowR
                        )

                        // Inner solid crisp white thumb with subtle border
                        drawCircle(
                            color = Color.White,
                            radius = thumbR,
                            center = thumbCenter
                        )
                        drawCircle(
                            color = Color(0x33000000),
                            radius = thumbR,
                            center = thumbCenter,
                            style = Stroke(width = 1f * density)
                        )
                    }
                }
            }

            Text(
                text = if (media.duration > 0L) formatMs(media.duration) else "—:—",
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF8E8E93),
                style = androidx.compose.ui.text.TextStyle(
                    fontFeatureSettings = "tnum"
                )
            )
        }

        // --- 3. Bottom Section: Centered Media Playback Controls ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(42.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = interactive,
                        onClick = { host.mediaAction("previous") }
                    ),
                contentAlignment = Alignment.Center
            ) {
                SkipPreviousIcon(
                    color = Color.White,
                    modifier = Modifier.size(24.dp, 16.dp)
                )
            }

            // Play / Pause Button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = interactive,
                        onClick = { host.mediaAction("toggle") }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (media.playing) {
                    PauseIcon(
                        color = Color.White,
                        modifier = Modifier.size(18.dp, 22.dp)
                    )
                } else {
                    PlayIcon(
                        color = Color.White,
                        modifier = Modifier.size(22.dp, 22.dp)
                    )
                }
            }

            // Next Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = interactive,
                        onClick = { host.mediaAction("next") }
                    ),
                contentAlignment = Alignment.Center
            ) {
                SkipNextIcon(
                    color = Color.White,
                    modifier = Modifier.size(24.dp, 16.dp)
                )
            }
        }
    }
}

@Composable
private fun WaveformEqualizer(
    playing: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    val h0 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w0"
    )
    val h1 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.98f,
        animationSpec = infiniteRepeatable(
            animation = tween(360, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.60f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(410, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w3"
    )
    val h4 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(460, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w4"
    )

    val animProgress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(240),
        label = "eqAnim"
    )

    val density = LocalDensity.current.density

    Canvas(modifier = modifier.size(28.dp, 24.dp)) {
        val totalW = size.width
        val totalH = size.height
        val barCount = 5
        val barW = 3.2f * density
        val spacing = (totalW - (barCount * barW)) / (barCount - 1)
        val minH = 3.5f * density

        val heights = floatArrayOf(h0, h1, h2, h3, h4)

        for (i in 0 until barCount) {
            val targetH = (minH + (totalH - minH) * heights[i]) * animProgress + minH * (1f - animProgress)
            val x = i * (barW + spacing)
            val y = (totalH - targetH) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, targetH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${if (seconds < 10) "0$seconds" else "$seconds"}"
}

@Composable
private fun SkipPreviousIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gap = w * 0.08f
        val triW = (w - gap) / 2f

        // Left triangle
        val path1 = Path().apply {
            moveTo(triW, 0f)
            lineTo(0f, h / 2f)
            lineTo(triW, h)
            close()
        }
        // Right triangle
        val path2 = Path().apply {
            moveTo(w, 0f)
            lineTo(w - triW, h / 2f)
            lineTo(w, h)
            close()
        }
        drawPath(path1, color)
        drawPath(path2, color)
    }
}

@Composable
private fun SkipNextIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gap = w * 0.08f
        val triW = (w - gap) / 2f

        // Left triangle
        val path1 = Path().apply {
            moveTo(0f, 0f)
            lineTo(triW, h / 2f)
            lineTo(0f, h)
            close()
        }
        // Right triangle
        val path2 = Path().apply {
            moveTo(w - triW, 0f)
            lineTo(w, h / 2f)
            lineTo(w - triW, h)
            close()
        }
        drawPath(path1, color)
        drawPath(path2, color)
    }
}

@Composable
private fun PauseIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.32f
        val cr = CornerRadius(barW / 2f, barW / 2f)
        // Left bar
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, 0f),
            size = Size(barW, h),
            cornerRadius = cr
        )
        // Right bar
        drawRoundRect(
            color = color,
            topLeft = Offset(w - barW, 0f),
            size = Size(barW, h),
            cornerRadius = cr
        )
    }
}

@Composable
private fun PlayIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, h / 2f)
            lineTo(0f, h)
            close()
        }
        drawPath(path, color)
    }
}

data class AlbumPalette(
    val topTint: Color = Color(0xFA121217),
    val bottomTint: Color = Color(0xFD09090C),
    val accentColor: Color = Color.White.copy(alpha = 0.88f),
    val hasColor: Boolean = false
)

private fun isYouTubePackage(pkg: String?): Boolean {
    if (pkg == null) return false
    val p = pkg.lowercase()
    return p.contains("youtube") || p.contains("vanced") || p.contains("newpipe")
}

private val YouTubePalette = AlbumPalette(
    topTint = Color(0xFA381014),      // Deep smoky ruby obsidian glass (98% opacity)
    bottomTint = Color(0xFD14080A),   // Deep charcoal obsidian glass (99% opacity)
    accentColor = Color(0xFFFF0033),  // Iconic YouTube Red
    hasColor = true
)

private fun extractAlbumPalette(bitmap: Bitmap?): AlbumPalette {
    val fallback = AlbumPalette()
    if (bitmap == null) return fallback
    return try {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return fallback

        var bestHue = 0f
        var bestSat = 0f
        var maxScore = -1f
        var colorFound = false

        val hsv = FloatArray(3)
        val steps = 8
        for (iy in 1 until steps) {
            val y = (h * iy) / steps
            for (ix in 1 until steps) {
                val x = (w * ix) / steps
                val px = bitmap.getPixel(x, y)
                android.graphics.Color.colorToHSV(px, hsv)
                val s = hsv[1]
                val v = hsv[2]

                // Consider pixels with noticeable color
                if (s >= 0.16f && v >= 0.16f) {
                    val score = s * 0.75f + v * 0.25f
                    if (score > maxScore) {
                        maxScore = score
                        bestHue = hsv[0]
                        bestSat = s
                        colorFound = true
                    }
                }
            }
        }

        if (!colorFound || bestSat < 0.16f) return fallback

        // Top tint: rich dark smoky obsidian glass (98% opacity)
        val topSat = (bestSat * 0.75f).coerceIn(0.35f, 0.60f)
        val topVal = 0.18f
        val topColorInt = android.graphics.Color.HSVToColor(0xFA, floatArrayOf(bestHue, topSat, topVal))

        // Bottom tint: deep charcoal obsidian glass (99% opacity)
        val botSat = topSat * 0.50f
        val botVal = 0.08f
        val botColorInt = android.graphics.Color.HSVToColor(0xFD, floatArrayOf(bestHue, botSat, botVal))

        // Vibrant accent for waveform & halo glow
        val accentSat = bestSat.coerceAtLeast(0.65f)
        val accentVal = 0.95f
        val accentColorInt = android.graphics.Color.HSVToColor(0xFF, floatArrayOf(bestHue, accentSat, accentVal))

        AlbumPalette(
            topTint = Color(topColorInt),
            bottomTint = Color(botColorInt),
            accentColor = Color(accentColorInt),
            hasColor = true
        )
    } catch (_: Exception) {
        fallback
    }
}
