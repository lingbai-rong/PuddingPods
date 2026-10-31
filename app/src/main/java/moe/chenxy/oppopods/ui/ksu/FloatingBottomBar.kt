// Synced from KernelSU manager FloatingBottomBar.kt (main branch, 2026-07-20).
// Upstream is adapted from compose-miuix-ui IosLiquidGlassNavigationBar (Apache 2.0).
// Host-specific code may choose tabs and navigation only; glass physics stay shared.

package moe.chenxy.oppopods.ui.ksu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.ui.ksu.liquid.InnerShadow
import moe.chenxy.oppopods.ui.ksu.liquid.innerShadow
import moe.chenxy.oppopods.ui.ksu.liquid.lens
import moe.chenxy.oppopods.ui.ksu.liquid.rememberCombinedBackdrop
import moe.chenxy.oppopods.ui.ksu.liquid.vibrancy
import moe.chenxy.oppopods.ui.ksu.miuix.animation.DampedDragAnimation
import moe.chenxy.oppopods.ui.ksu.miuix.animation.InteractiveHighlight
import moe.chenxy.oppopods.ui.ksu.isInDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }
private val LocalFloatingBottomBarInteractive = staticCompositionLocalOf { true }

private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

// Mirrors miuix-blur HighlightStyle's LIGHT_REF — keep in sync.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, ≈ 6° tilt

/** Tracks gravity for a `dualPeak` highlight's primary light, with an extra UV-clockwise offset on top. */
@Composable
private fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f,
): Highlight {
    val baseStyle = base.style as BloomStroke
    val tilt by rememberDeviceTilt()
    val rotatedPrimary = remember(tilt, baseStyle.primaryLight, extraDegrees) {
        val basePrimary = baseStyle.primaryLight
        val gx = tilt.gravityX
        val gy = tilt.gravityY
        val gMagSq = gx * gx + gy * gy
        val (lx0, ly0) = if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
            val invMag = 1f / sqrt(gMagSq)
            (gx * invMag) to (gy * invMag)
        } else {
            0f to -1f
        }
        val rad = extraDegrees * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = c * lx0 - s * ly0
        val ly = s * lx0 + c * ly0
        basePrimary.copy(
            position = LightPosition(
                x = LIGHT_REF_X + lx,
                y = LIGHT_REF_Y + ly,
                z = basePrimary.position.z,
            ),
        )
    }
    return remember(base, rotatedPrimary) {
        base.copy(style = baseStyle.copy(primaryLight = rotatedPrimary))
    }
}

@Composable
fun RowScope.FloatingBottomBarItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current
    val isInteractive = LocalFloatingBottomBarInteractive.current
    Column(
        modifier
            .then(
                if (isInteractive) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun RowScope.FloatingActionBottomBarItem(
    onClick: () -> Unit,
    backdrop: Backdrop,
    enabled: Boolean = true,
    isBlurEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "fileActionPress",
    )
    val contentColor = LocalContentColor.current
    val inheritedLensScale = LocalFloatingBottomBarTabScale.current
    val isInteractive = LocalFloatingBottomBarInteractive.current

    // The pressed glass is a separate layer behind the icon/label. Applying layer alpha to the
    // whole item made the normal content alpha zero, so the icon only appeared while pressed.
    Box(
        modifier
            .fillMaxHeight()
            .weight(1f)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .graphicsLayer {
                // In native-lens mode the whole navigation row is duplicated inside the moving
                // ellipse. Inherit that lens scale so special centre actions (for example the
                // green "+" button) enlarge together with ordinary tab icons instead of staying
                // at their original size while only the blur shell grows.
                val localScale = inheritedLensScale()
                val ownPressScale = lerp(1f, 1.12f, pressProgress)
                scaleX = localScale * ownPressScale
                scaleY = localScale * ownPressScale
            }
            .then(
                if (isInteractive) {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isBlurEnabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            val progress = pressProgress
                            vibrancy()
                            blur(2.dp.toPx() * progress, 2.dp.toPx() * progress)
                            lens(
                                refractionHeight = 8.dp.toPx() * progress,
                                refractionAmount = 10.dp.toPx() * progress,
                                depthEffect = true,
                                chromaticAberration = 0.35f,
                            )
                        },
                        layerBlock = { alpha = pressProgress },
                        onDrawSurface = {
                            drawRect(contentColor.copy(alpha = 0.08f * pressProgress))
                        },
                    )
            )
        } else if (pressProgress > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        contentColor.copy(alpha = 0.10f * pressProgress),
                        CircleShape,
                    )
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * Selection-mode action row for File Explorer. It deliberately has no persistent selected tab:
 * every command is an independent action, while the container uses the same KSU/Miuix backdrop,
 * refraction, highlight and shadow pipeline as the normal navigation row.
 */
@Composable
fun FloatingActionBottomBar(
    modifier: Modifier = Modifier,
    backdrop: Backdrop,
    isBlurEnabled: Boolean = true,
    isNativeBackdropEnabled: Boolean = false,
    forceDark: Boolean? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val isInDark = forceDark ?: isInDarkTheme()
    val pillShape = remember { CircleShape }
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val containerColor = when {
        // The Android native View below already contains the live blurred host pixels. Any
        // additional surface fill here turns bright pages into a flat grey slab.
        isNativeBackdropEnabled -> Color.Transparent
        isBlurEnabled -> surfaceContainer.copy(0.4f)
        else -> surfaceContainer
    }
    val baseHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)

    Row(
        modifier
            .dropShadow(
                shape = pillShape,
                shadow = Shadow(
                    radius = 10.dp,
                    color = Color.Black,
                    alpha = if (isInDark) 0.2f else 0.1f,
                ),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .then(
                if (isBlurEnabled && !isNativeBackdropEnabled) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { pillShape },
                        effects = {
                            vibrancy()
                            blur(4.dp.toPx(), 4.dp.toPx())
                            lens(
                                refractionHeight = 24.dp.toPx(),
                                refractionAmount = 24.dp.toPx(),
                            )
                        },
                        highlight = { baseHighlight.copy(alpha = 0.75f) },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                } else {
                    Modifier.background(containerColor, pillShape)
                }
            )
            .height(64.dp)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurface) {
            content()
        }
    }
}

@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    blockedIndex: Int = -1,
    restoreOnBlockedIndex: Boolean = false,
    isBlurEnabled: Boolean = true,
    isNativeBackdropEnabled: Boolean = false,
    nativeBlurRadiusPx: Int = 18,
    forceDark: Boolean? = null,
    selectedAccentEnabled: Boolean = false,
    transparentBackdropSurface: Boolean = false,
    pressOnlyLiquidBackdrop: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val isInDark = forceDark ?: isInDarkTheme()
    val pillShape = remember { CircleShape }
    val accentColor = if (selectedAccentEnabled) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val containerColor = when {
        // 26.66 Douyin press-only mode deliberately matches the separate "+" button's idle
        // transparency instead of showing the captured Bitmap as an always-on glass surface.
        pressOnlyLiquidBackdrop -> if (isInDark) Color(0x59000000) else Color(0x59FFFFFF)
        // The Android native View below already contains the live blurred host pixels. Any
        // additional surface fill here turns bright pages into a flat grey slab.
        isNativeBackdropEnabled || transparentBackdropSurface -> Color.Transparent
        isBlurEnabled -> surfaceContainer.copy(0.4f)
        else -> surfaceContainer
    }

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    val safeSelectedIndex = selectedIndex().coerceIn(0, tabsCount - 1)
    var currentIndex by remember(tabsCount) { mutableIntStateOf(safeSelectedIndex) }

    class DampedDragAnimationHolder {
        var instance: DampedDragAnimation? = null
    }

    val holder = remember { DampedDragAnimationHolder() }

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr, blockedIndex, restoreOnBlockedIndex) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = safeSelectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                if (tabWidthPx == 0f) return@DampedDragAnimation false

                val currentValue = anim.value
                val indicatorX = currentValue * tabWidthPx
                val padding = with(density) { 4.dp.toPx() }
                val globalTouchX = if (isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = {},
            onDragStopped = {
                val rawTarget = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                val droppedOnBlocked = blockedIndex in 0 until tabsCount && rawTarget == blockedIndex
                val targetIndex = when {
                    droppedOnBlocked && restoreOnBlockedIndex -> currentIndex
                    droppedOnBlocked && currentIndex < blockedIndex ->
                        (blockedIndex + 1).coerceAtMost(tabsCount - 1)
                    droppedOnBlocked ->
                        (blockedIndex - 1).coerceAtLeast(0)
                    else -> rawTarget
                }
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                onSelected(targetIndex)
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            }
        ).also { holder.instance = it }
    }

    LaunchedEffect(selectedIndex, tabsCount) {
        snapshotFlow { selectedIndex().coerceIn(0, tabsCount - 1) }
            .collectLatest { currentIndex = it }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            // External pager synchronization only moves the glass. Navigation callbacks are
            // emitted solely by actual tab clicks or by onDragStopped, otherwise the temporary
            // middle page of a 0 -> 2 pager animation feeds back and cancels the jump at page 1.
            dampedDragAnimation.animateToValue(index.toFloat())
        }
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            }
        )
    }

    val baseHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)
    val pillHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = 90f)

    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                }
                .graphicsLayer { translationX = panelOffset }
                .dropShadow(
                    shape = pillShape,
                    shadow = Shadow(
                        radius = 10.dp,
                        color = Color.Black,
                        alpha = if (isInDark) 0.2f else 0.1f,
                    ),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .then(
                    if (isBlurEnabled && !isNativeBackdropEnabled && !pressOnlyLiquidBackdrop) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            highlight = { baseHighlight.copy(alpha = 0.75f) },
                            layerBlock = {
                                val width = size.width.coerceAtLeast(1f)
                                val s = lerp(1f, 1f + 16.dp.toPx() / width, dampedDragAnimation.pressProgress)
                                scaleX = s
                                scaleY = s
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, pillShape)
                    }
                )
                .then(if (isBlurEnabled || isNativeBackdropEnabled) interactiveHighlight.modifier else Modifier)
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                // Native mode and the no-capture mode render a transformed replacement scene
                // inside the moving oval. Remove the normal icon/text pixels only from that oval
                // first, otherwise both copies remain visible and produce the reported ghosting.
                // The bar surface itself stays untouched; only navigation content gets a hole.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (!isBlurEnabled && tabWidthPx > 0f) {
                                Modifier.drawWithContent {
                                    val velocity = dampedDragAnimation.velocity / 10f
                                    val velocityScaleX = 1f -
                                        (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                    val velocityScaleY = 1f -
                                        (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                                    val ellipseScaleX = dampedDragAnimation.scaleX / velocityScaleX
                                    val ellipseScaleY = dampedDragAnimation.scaleY * velocityScaleY
                                    val ellipseWidthPx = tabWidthPx * ellipseScaleX
                                    val ellipseHeightPx = size.height * ellipseScaleY
                                    val ellipseCenterX = if (isLtr) {
                                        (dampedDragAnimation.value + 0.5f) * tabWidthPx
                                    } else {
                                        size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx
                                    }
                                    val ellipseCenterY = size.height / 2f
                                    val halfWidth = ellipseWidthPx / 2f
                                    val halfHeight = ellipseHeightPx / 2f
                                    val radius = halfHeight.coerceAtLeast(0f)
                                    val ovalPath = Path().apply {
                                        addRoundRect(
                                            RoundRect(
                                                ellipseCenterX - halfWidth,
                                                ellipseCenterY - halfHeight,
                                                ellipseCenterX + halfWidth,
                                                ellipseCenterY + halfHeight,
                                                CornerRadius(radius, radius),
                                                CornerRadius(radius, radius),
                                                CornerRadius(radius, radius),
                                                CornerRadius(radius, radius),
                                            )
                                        )
                                    }
                                    clipPath(ovalPath, clipOp = ClipOp.Difference) {
                                        this@drawWithContent.drawContent()
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }

        if (isBlurEnabled) {
            CompositionLocalProvider(
                LocalFloatingBottomBarTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                },
                LocalContentColor provides accentColor,
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(interactiveHighlight.modifier)
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }

        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            if (isBlurEnabled) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                            // At rest the selected lens is fully invisible, leaving only the same
                            // translucent shell as Douyin's separate "+". Pressing fades the real
                            // Bitmap lens in while DampedDragAnimation expands it to 1.2x.
                            alpha = if (pressOnlyLiquidBackdrop) {
                                dampedDragAnimation.pressProgress
                            } else {
                                1f
                            }
                        }
                        .then(interactiveHighlight.gestureModifier)
                        .then(dampedDragAnimation.modifier)
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { pillShape },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                lens(
                                    refractionHeight = 10.dp.toPx() * progress,
                                    refractionAmount = 14.dp.toPx() * progress,
                                    depthEffect = true,
                                    chromaticAberration = 0.5f,
                                )
                            },
                            highlight = { pillHighlight.copy(alpha = dampedDragAnimation.pressProgress) },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                if (!pressOnlyLiquidBackdrop) {
                                    drawRect(
                                        color = if (!isInDark) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                                        alpha = 1f - progress,
                                    )
                                }
                                // In press-only mode the selection is pixel-identical to the host
                                // at rest. Once pressed, retain only a tiny depth tint while the
                                // 1.2x lens/refraction/chromatic-aberration effects become visible.
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                        .innerShadow(shape = pillShape) {
                            InnerShadow(
                                radius = 8.dp * dampedDragAnimation.pressProgress,
                                color = Color.Black.copy(alpha = 0.15f),
                                alpha = dampedDragAnimation.pressProgress,
                            )
                        }
                        .height(56.dp)
                        .width(tabWidthDp)
                )
            } else {
                // The enlarged native-mode lens deliberately has no independent blur surface.
                // The full bottom bar below still uses HyperOS native blur, while the moving
                // selection is one coherent white oval. Removing the nested AndroidView avoids
                // the rectangular/square blur patch that used to appear inside the ellipse.

                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                        }
                        .then(interactiveHighlight.gestureModifier)
                        .then(dampedDragAnimation.modifier)
                        .graphicsLayer {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        }
                        .clip(pillShape)
                        .background(
                            when {
                                isNativeBackdropEnabled && isInDark ->
                                    Color.White.copy(alpha = 0.030f)
                                isNativeBackdropEnabled ->
                                    // Requested light-mode frost: the Compose surface is a true
                                    // Keep the moving native lens deliberately very transparent.
                                    // The full bar below already carries the stronger HyperOS blur;
                                    // this oval only provides shape, highlight and press feedback.
                                    Color.White.copy(alpha = 0.080f)
                                else -> accentColor.copy(alpha = 0.080f)
                            },
                            pillShape,
                        )
                        .then(
                            if (isNativeBackdropEnabled) {
                                Modifier
                                    .border(
                                        width = 0.75.dp,
                                        color = if (isInDark) {
                                            Color.White.copy(alpha = 0.12f)
                                        } else {
                                            Color.White.copy(alpha = 0.34f)
                                        },
                                        shape = pillShape,
                                    )
                                    .drawWithContent {
                                        drawContent()
                                        val progress = dampedDragAnimation.pressProgress
                                            .fastCoerceIn(0f, 1f)
                                        val radius = size.height / 2f

                                        // A soft white body remains visible before pressing; the
                                        // bloom strengthens on press like the captured refraction
                                        // surface, without sampling the host into an ImageBitmap.
                                        drawRoundRect(
                                            color = Color.White.copy(
                                                alpha = if (isInDark) {
                                                    0.012f + 0.024f * progress
                                                } else {
                                                    0.008f + 0.022f * progress
                                                },
                                            ),
                                            cornerRadius = CornerRadius(radius, radius),
                                            blendMode = BlendMode.Plus,
                                        )
                                        val strokePx = lerp(0.55.dp.toPx(), 0.90.dp.toPx(), progress)
                                        drawRoundRect(
                                            color = Color.White.copy(
                                                alpha = if (isInDark) {
                                                    0.13f + 0.14f * progress
                                                } else {
                                                    0.30f + 0.18f * progress
                                                },
                                            ),
                                            cornerRadius = CornerRadius(radius, radius),
                                            style = Stroke(strokePx),
                                        )
                                        // Approximate the captured lens fringe: light cyan on the
                                        // leading edge and warm magenta on the trailing edge.
                                        val fringeStroke = lerp(0.30.dp.toPx(), 0.60.dp.toPx(), progress)
                                        val fringeDx = lerp(0.25.dp.toPx(), 1.10.dp.toPx(), progress)
                                        val fringeDy = lerp(0.15.dp.toPx(), 0.55.dp.toPx(), progress)
                                        drawRoundRect(
                                            color = Color(0xFF64C8FF).copy(alpha = if (isInDark) 0.025f + 0.035f * progress else 0.018f + 0.035f * progress),
                                            topLeft = Offset(-fringeDx, -fringeDy),
                                            size = size,
                                            cornerRadius = CornerRadius(radius, radius),
                                            style = Stroke(fringeStroke),
                                            blendMode = BlendMode.Plus,
                                        )
                                        drawRoundRect(
                                            color = Color(0xFFFFA0D8).copy(alpha = if (isInDark) 0.020f + 0.030f * progress else 0.016f + 0.030f * progress),
                                            topLeft = Offset(fringeDx * 0.9f, -fringeDy * 0.5f),
                                            size = size,
                                            cornerRadius = CornerRadius(radius, radius),
                                            style = Stroke(fringeStroke),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                            } else {
                                Modifier
                            }
                        )
                        .innerShadow(shape = pillShape) {
                            InnerShadow(
                                radius = 6.dp * dampedDragAnimation.pressProgress,
                                color = Color.Black.copy(
                                    alpha = if (isInDark) 0.065f else 0.012f,
                                ),
                                alpha = dampedDragAnimation.pressProgress,
                            )
                        }
                        .height(56.dp)
                        .width(tabWidthDp)
                )



            }

            if (!isBlurEnabled) {
                // One replacement scene for both native blur and "background capture off".
                // The normal row has already been cut out through this same oval, so each pixel
                // now contains exactly one icon/text copy. This removes the stationary + scaled
                // double image while preserving the whole-oval magnification.
                val baseIndicatorHeightPx = with(density) { 56.dp.toPx() }
                val rowHeightPx = with(density) { 64.dp.toPx() }
                val velocity = dampedDragAnimation.velocity / 10f
                val velocityScaleX = 1f -
                    (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                val velocityScaleY = 1f -
                    (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                val ellipseScaleX = dampedDragAnimation.scaleX / velocityScaleX
                val ellipseScaleY = dampedDragAnimation.scaleY * velocityScaleY
                val ellipseWidthPx = tabWidthPx * ellipseScaleX
                val ellipseHeightPx = baseIndicatorHeightPx * ellipseScaleY
                val contentWidthPx = (totalWidthPx - with(density) { 8.dp.toPx() })
                    .coerceAtLeast(1f)
                val localCenterX = if (isLtr) {
                    (dampedDragAnimation.value + 0.5f) * tabWidthPx
                } else {
                    contentWidthPx - (dampedDragAnimation.value + 0.5f) * tabWidthPx
                }
                val ellipseCenterX = with(density) { 4.dp.toPx() } + localCenterX
                val ellipseCenterY = rowHeightPx / 2f
                val press = dampedDragAnimation.pressProgress.fastCoerceIn(0f, 1f)
                val ovalSceneScaleX = lerp(1f, 1.20f, press)
                val ovalSceneScaleY = lerp(1f, 1.20f, press)
                val contentTransformOriginX = if (contentWidthPx > 0f) {
                    (localCenterX / contentWidthPx).fastCoerceIn(0f, 1f)
                } else {
                    0.5f
                }

                CompositionLocalProvider(
                    LocalFloatingBottomBarTabScale provides { 1f },
                    LocalFloatingBottomBarInteractive provides false,
                    LocalContentColor provides accentColor,
                ) {
                    Row(
                        Modifier
                            .clearAndSetSemantics {}
                            .graphicsLayer { translationX = panelOffset }
                            .drawWithContent {
                                val halfWidth = ellipseWidthPx / 2f
                                val halfHeight = ellipseHeightPx / 2f
                                val radius = halfHeight.coerceAtLeast(0f)
                                val ovalPath = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            ellipseCenterX - halfWidth,
                                            ellipseCenterY - halfHeight,
                                            ellipseCenterX + halfWidth,
                                            ellipseCenterY + halfHeight,
                                            CornerRadius(radius, radius),
                                            CornerRadius(radius, radius),
                                            CornerRadius(radius, radius),
                                            CornerRadius(radius, radius),
                                        )
                                    )
                                }
                                clipPath(ovalPath) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                            .graphicsLayer {
                                scaleX = ovalSceneScaleX
                                scaleY = ovalSceneScaleY
                                transformOrigin = TransformOrigin(contentTransformOriginX, 0.5f)
                            }
                            .height(64.dp)
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = content,
                    )
                }
            }
        }
    }
}
