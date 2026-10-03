// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.settings.dialogs.LocalKeepKeyboard

import androidx.compose.ui.window.PopupProperties

import androidx.compose.foundation.background
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.utils.ExpandButton
import helium314.keyboard.latin.utils.dpToPx

@Composable
fun WithSmallTitle(
    description: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(description, style = MaterialTheme.typography.titleSmall,
            fontStyle = if (helium314.keyboard.settings.preferences.LocalPendingChange.current) androidx.compose.ui.text.font.FontStyle.Italic else null)
        content()
    }
}

/** A section with a prominent heading, for the main sections of a screen. */
@Composable
fun WithBigTitle(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        // a line above every group, as on the list screens
        androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        content()
    }
}

@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit
) {
    val clickableModifier = if (onClick != null) Modifier.clickable(onClick = onClick)
    else Modifier
    Row(
        modifier = modifier
            .then(clickableModifier)
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = verticalAlignment,
        content = content
    )
}

/** Icon if resource is a vector image, (bitmap) Image otherwise */
@Composable
fun IconOrImage(@DrawableRes resId: Int, name: String?, sizeDp: Int) {
    val ctx = LocalContext.current
    val drawable = ContextCompat.getDrawable(ctx, resId)
    if (drawable is VectorDrawable)
        Icon(painterResource(resId), name, Modifier.size(sizeDp.dp))
    else {
        val px = sizeDp.dpToPx(LocalResources.current)
        Image(drawable!!.toBitmap(px, px).asImageBitmap(), name)
    }
}

@Composable
fun KeyboardIconsSet.GetIconOrEmpty(name: String?) {
    val iconId = iconIds[name?.lowercase()]
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (iconId != null)
            Icon(painterResourceCompat(iconId), name, Modifier.fillMaxSize(0.8f))
    }
}

/** [painterResource], but also works for non-vector/bitmaps (needs a size for bitmap creation) */
@Composable
fun painterResourceCompat(@DrawableRes resId: Int, sizeDp: Int = 40): Painter {
    val ctx = LocalContext.current
    val drawable = ContextCompat.getDrawable(ctx, resId)
    return if (drawable is VectorDrawable || drawable is BitmapDrawable)
        painterResource(resId)
    else {
        val px = sizeDp.dpToPx(LocalResources.current)
        BitmapPainter(drawable!!.toBitmap(px, px).asImageBitmap())
    }
}

@Composable
fun <T>DropDownField(
    items: List<T>,
    selectedItem: T,
    onSelected: (T) -> Unit,
    extraButton: @Composable (() -> Unit)? = null,
    itemTrailing: @Composable ((T) -> Unit)? = null, // e.g. a delete button, in the menu only
    // a row's look for the closed field (a title with the choice under it, like the rows next to it); null: the item
    fieldContent: @Composable ((T) -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        Modifier.clickable { expanded = !expanded }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (fieldContent != null) Modifier.padding(start = 10.dp) else Modifier.padding(start = 8.dp, bottom = 4.dp)
        ) {
            Box(Modifier.weight(1f)) {
                (fieldContent ?: itemContent)(selectedItem)
            }
            ExpandButton(items.size > 1) { expanded = !expanded }
            if (extraButton != null)
                extraButton()
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        // on a screen whose keyboard is the preview the menu must not take the focus, or the keyboard goes away
        properties = PopupProperties(focusable = !LocalKeepKeyboard.current),
    ) {
        items.forEach {
            DropdownMenuItem(
                text = { itemContent(it) },
                onClick = { expanded = false; onSelected(it) },
                trailingIcon = itemTrailing?.let { trailing -> { trailing(it) } },
            )
        }
    }
}

@Composable
fun isWideScreen(): Boolean {
    val width = LocalConfiguration.current.screenWidthDp
    val height = LocalConfiguration.current.screenHeightDp
    return height < 500 && width > height
}

val contentTextDirectionStyle = TextStyle(textDirection = TextDirection.Content)

/** The background of settings shown only in advanced mode, so toggling the mode shows what it adds. */
@Composable
fun advancedTint() = MaterialTheme.colorScheme.surfaceContainerHigh

/** An advanced-only item (or run of items) on the advanced tint, edge to edge of its parent. With [visible] (the
 *  advanced mode) it unfolds and folds away with the mode switch instead of popping in and out. */
@Composable
fun AdvancedTint(visible: Boolean? = null, content: @Composable () -> Unit) {
    if (visible == null) Column(Modifier.fillMaxWidth().background(advancedTint())) { content() }
    else AdvancedReveal(visible) { Column(Modifier.fillMaxWidth().background(advancedTint())) { content() } }
}

/** Rows that come and go with the advanced mode: they unfold downwards (and fade in) when it's turned on, and fold
 *  up (fading out) when it's turned off, so the rows below slide rather than jump. */
@Composable
fun AdvancedReveal(visible: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(ADVANCED_ANIM_MS),
            expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ADVANCED_ANIM_MS)),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(ADVANCED_ANIM_MS),
            shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ADVANCED_ANIM_MS)),
    ) { content() }
}

private const val ADVANCED_ANIM_MS = 280

/**
 * A thin scroll bar at the right edge of a list in a dialog, shown while there's more to scroll (2026-10-03:
 * without one, a short list that scrolls inside looks complete). Length = the share shown, position = where it is.
 * Put it on the list (the viewport), before any scrolling modifier.
 */
@Composable
fun Modifier.scrollbar(state: androidx.compose.foundation.lazy.LazyListState): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    return this.drawWithContent {
        drawContent()
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount == 0 || (!state.canScrollForward && !state.canScrollBackward)) return@drawWithContent
        val itemHeight = visible.sumOf { it.size }.toFloat() / visible.size
        val content = itemHeight * info.totalItemsCount
        val scrolled = state.firstVisibleItemIndex * itemHeight + state.firstVisibleItemScrollOffset
        drawScrollbar(color, scrolled, content)
    }
}

/** [scrollbar] for a scrolling column ([state] of its verticalScroll). */
@Composable
fun Modifier.scrollbar(state: androidx.compose.foundation.ScrollState): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    return this.drawWithContent {
        drawContent()
        if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return@drawWithContent
        drawScrollbar(color, state.value.toFloat(), size.height + state.maxValue)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrollbar(color: androidx.compose.ui.graphics.Color, scrolled: Float, content: Float) {
    val view = size.height
    if (content <= view) return
    val thumb = (view * view / content).coerceIn(24.dp.toPx().coerceAtMost(view), view)
    val top = (scrolled / (content - view)).coerceIn(0f, 1f) * (view - thumb)
    val width = 3.dp.toPx()
    drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width - width - 1.dp.toPx(), top),
        size = androidx.compose.ui.geometry.Size(width, thumb), cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2))
}
