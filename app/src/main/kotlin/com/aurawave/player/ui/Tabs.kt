package com.aurawave.player.ui

import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.player.data.Settings
import kotlinx.coroutines.launch

/**
 * The tabs along the top. By default the app opens on Home, in the middle: the listener's
 * own music lies to its left, nearest first, and what comes from online lies to its right.
 * The order, and the tab the app opens on, are the listener's to change.
 */
enum class Tab(val label: String) {
    Artists("Artists"),
    Albums("Albums"),
    Downloads("Downloads"),
    Playlists("Playlists"),
    Liked("Liked"),
    Home("Home"),
    Discover("Discover"),
    Podcasts("Podcasts");

    companion object {
        /** The listener's order as saved in Settings. Tabs not in it (added in a later version) go at the end. */
        fun ordered(names: List<String>): List<Tab> {
            val chosen = names.mapNotNull { name -> named(name) }.distinct()
            return chosen + entries.filter { it !in chosen }
        }

        fun named(name: String): Tab? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The app's main screen, in the manner of One UI: the name with search and a menu beside
 * it, a row of tabs where the one showing is large, and the tabs themselves, side by side,
 * to swipe between. There is no bar along the bottom; that space is the mini player's.
 */
@Composable
fun TabsHome(
    tabs: List<Tab>,
    pager: PagerState,
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable (Tab) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The app's name in its text face, at a medium weight: a quiet label, not a headline.
            Text(
                "AuraWave",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, contentDescription = "Search") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Listening history") }, onClick = { menuOpen = false; onHistory() })
                    DropdownMenuItem(text = { Text("Listening stats") }, onClick = { menuOpen = false; onStats() })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                }
            }
        }
        TabStrip(tabs, selected = pager.currentPage, onSelect = { i -> scope.launch { pager.animateScrollToPage(i) } })
        // The tabs sit on one rounded sheet, a shade lighter than the page, as One UI lays out its lists.
        HorizontalPager(
            state = pager,
            key = { tabs[it].name },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Palette.Dusk),
        ) { i ->
            Box(Modifier.fillMaxSize()) { content(tabs[i]) }
        }
    }
}

/** The tab names in a row that scrolls sideways. The one showing grows and takes the accent colour. */
@Composable
private fun TabStrip(tabs: List<Tab>, selected: Int, onSelect: (Int) -> Unit) {
    val list = rememberLazyListState()
    // The tab showing sits in the middle of the row, with its neighbours on both sides, as in One UI.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        if (list.layoutInfo.visibleItemsInfo.none { it.index == selected }) list.scrollToItem(selected)
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected } ?: return@LaunchedEffect
        val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val by = item.offset + item.size / 2f - middle
        // The first time, it is simply there; after that it glides.
        if (placed) list.animateScrollBy(by) else list.scrollBy(by)
        placed = true
    }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(tabs, key = { _, t -> t.name }) { i, tab ->
            val on = i == selected
            val size by animateFloatAsState(if (on) 19f else 15.5f, tween(200), label = "tabSize")
            val color by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(200),
                label = "tabColor",
            )
            Text(
                tab.label,
                fontSize = size.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                color = color,
                maxLines = 1,
                modifier = Modifier
                    .semantics { this.selected = on }
                    .clickable(role = Role.Tab, onClickLabel = "Show ${tab.label}") { onSelect(i) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

/** Settings: the order of the tabs, and the one the app opens on. */
@Composable
fun TabOrderSettings(settings: Settings) {
    val saved by settings.tabOrder.flow.collectAsStateWithLifecycle()
    val openName by settings.openTab.flow.collectAsStateWithLifecycle()
    val order = Tab.ordered(saved)
    val opensOn = Tab.named(openName) ?: Tab.Home
    fun move(from: Int, to: Int) {
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        settings.tabOrder.value = list.map { it.name }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Tap a tab to open the app on it. Use the arrows to move tabs left or right.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        order.forEachIndexed { i, tab ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = tab == opensOn, role = Role.RadioButton) { settings.openTab.value = tab.name }
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = tab == opensOn, onClick = null, modifier = Modifier.padding(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tab.label, style = MaterialTheme.typography.bodyLarge)
                    if (tab == opensOn) Text("The app opens here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { move(i, i - 1) }, enabled = i > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move ${tab.label} left")
                }
                IconButton(onClick = { move(i, i + 1) }, enabled = i < order.lastIndex) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move ${tab.label} right")
                }
            }
        }
        if ((saved.isNotEmpty() && order != Tab.entries.toList()) || opensOn != Tab.Home) {
            TextButton(onClick = { settings.tabOrder.value = emptyList(); settings.openTab.value = Tab.Home.name }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Reset to the usual order")
            }
        }
    }
}
