package com.aurawave.player.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Drag-to-reorder for a LazyColumn. The list keeps its own copy of the items; [onMove]
 * moves one within that copy while the finger is down, and [onDrop] saves the result.
 * Items before [firstIndex] (headers) never move.
 */
@Stable
class ReorderState internal constructor(
    val listState: LazyListState,
    private val firstIndex: Int,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    internal fun start(key: Any) {
        draggingKey = key
        offset = 0f
    }

    internal fun dragBy(delta: Float) {
        offset += delta
        swapIfNeeded()
    }

    internal fun stop() {
        if (draggingKey == null) return
        draggingKey = null
        offset = 0f
        onDrop()
    }

    private fun swapIfNeeded() {
        val key = draggingKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.key == key } ?: return
        val center = dragged.offset + offset + dragged.size / 2f
        val target = items.firstOrNull {
            it.key != key && it.index >= firstIndex && center >= it.offset && center < it.offset + it.size
        } ?: return
        // The list keeps the first visible item in place; without this, moving that item scrolls the list with it.
        if (dragged.index == listState.firstVisibleItemIndex || target.index == listState.firstVisibleItemIndex) {
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
        onMove(dragged.index - firstIndex, target.index - firstIndex)
        offset += dragged.offset - target.offset
    }

    /** How fast to scroll when the dragged item is held near the top or bottom edge (pixels per frame, signed). */
    internal fun edgeSpeed(): Float {
        val key = draggingKey ?: return 0f
        val info = listState.layoutInfo
        val dragged = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        val top = dragged.offset + offset
        val bottom = top + dragged.size
        val edge = dragged.size.toFloat()
        return when {
            top < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - top) / edge).coerceAtMost(1f) * MAX_SPEED
            bottom > info.viewportEndOffset - edge -> ((bottom - (info.viewportEndOffset - edge)) / edge).coerceAtMost(1f) * MAX_SPEED
            else -> 0f
        }
    }

    internal suspend fun autoScroll() {
        while (draggingKey != null) {
            val speed = edgeSpeed()
            if (speed != 0f) {
                val moved = listState.scrollBy(speed)
                if (moved != 0f) {
                    offset += moved
                    swapIfNeeded()
                }
            }
            delay(16)
        }
    }

    private companion object {
        const val MAX_SPEED = 24f
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    firstIndex: Int = 0,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
): ReorderState {
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val state = remember(listState, firstIndex) { ReorderState(listState, firstIndex, { a, b -> move(a, b) }, { drop() }) }
    LaunchedEffect(state, state.draggingKey != null) {
        if (state.draggingKey != null) state.autoScroll()
    }
    return state
}

/** Put on each reorderable item: lifts the dragged one and lets the others slide into place. */
@Composable
fun LazyItemScope.reorderableItem(state: ReorderState, key: Any): Modifier =
    if (state.draggingKey == key) {
        Modifier.zIndex(1f).graphicsLayer { translationY = state.offset; shadowElevation = 8f }
    } else {
        Modifier.animateItem()
    }

/** Put on the item's drag handle. */
fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDrag = { change, amount ->
            change.consume()
            state.dragBy(amount.y)
        },
        onDragEnd = { state.stop() },
        onDragCancel = { state.stop() },
    )
}
