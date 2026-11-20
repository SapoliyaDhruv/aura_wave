package com.aurawave.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection

/**
 * The four places along the bottom. Home is where the listener left off and what is new;
 * the listener's own music is all in Library; Settings is reached from Library.
 */
enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    Home("Home", Icons.Rounded.Home, Icons.Outlined.Home),
    Search("Search", Icons.Rounded.Search, Icons.Rounded.Search),
    Library("Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
    Podcasts("Podcasts", Icons.Rounded.Podcasts, Icons.Outlined.Podcasts),
}

/**
 * The bar along the bottom. Each tab is an icon over its name, with room above and below.
 * The tab showing has its icon filled and a soft pill of the accent colour behind it, which
 * grows out from the middle when the tab is chosen; its name turns bright. A press dips
 * the icon slightly, so a tap is felt even on the tab already showing.
 */
@Composable
fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, color: Color = Palette.Ink) {
    Row(
        modifier
            .fillMaxWidth()
            .background(color)
            .navigationBarsPadding()
            .heightIn(min = BOTTOM_BAR_HEIGHT)
            .padding(horizontal = 8.dp),
    ) {
        Tab.entries.forEach { tab ->
            BarItem(tab, on = tab == selected, onClick = { onSelect(tab) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BarItem(tab: Tab, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    // The pill grows out from the middle with a little give, and shrinks back without it.
    val shown by animateFloatAsState(
        if (on) 1f else 0f,
        if (on) spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) else tween(160),
        label = "tabPill",
    )
    val dip by animateFloatAsState(if (pressed) 0.88f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "tabPress")
    val accent = MaterialTheme.colorScheme.primary
    val iconTint by animateColorAsState(if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant, tween(200), label = "tabIcon")
    val labelTint by animateColorAsState(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, tween(200), label = "tabLabel")
    Column(
        modifier
            .selectable(selected = on, role = Role.Tab, interactionSource = press, indication = null, onClick = onClick)
            // The same air above the pill as below the name, at any text size.
            .padding(top = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 60.dp, height = 32.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 0.5f + 0.5f * shown
                        alpha = shown
                    }
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
            )
            Icon(
                if (on) tab.on else tab.off,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp).graphicsLayer { scaleX = dip; scaleY = dip },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.2.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
            color = labelTint,
            maxLines = 1,
        )
    }
}

/**
 * The bar's least height above the phone's navigation bar: 12 dp of air, the pill, the name,
 * and 12 dp again. Larger text makes the bar taller rather than pushing the names off its edge.
 */
val BOTTOM_BAR_HEIGHT = 74.dp

/** What Library shows; one chip each, in the order they are reached for most. */
enum class Shelf(val label: String) {
    Liked("Liked"),
    Playlists("Playlists"),
    Downloads("Downloads"),
    Albums("Albums"),
    Artists("Artists"),
    History("History"),
}

/**
 * The listener's own music in one place: a row of chips to choose what to see, and the
 * list below. Settings is the gear beside the title.
 */
@Composable
fun LibraryTab(graph: AppGraph, player: PlayerConnection, onOpenPlaylist: (Long) -> Unit, onSettings: () -> Unit) {
    var shelfName by rememberSaveable { mutableStateOf(Shelf.Liked.name) }
    val shelf = Shelf.entries.firstOrNull { it.name == shelfName } ?: Shelf.Liked
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Library",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            items(Shelf.entries, key = { it.name }) { s ->
                FilterChip(
                    selected = s == shelf,
                    onClick = { shelfName = s.name },
                    label = { Text(s.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Palette.OnAura,
                    ),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (shelf) {
                Shelf.Liked -> LikedTab(graph, player)
                Shelf.Playlists -> PlaylistsTab(graph, player, onOpenPlaylist)
                Shelf.Downloads -> DownloadsTab(graph, player)
                Shelf.Albums -> AlbumsTab(graph)
                Shelf.Artists -> ArtistsTab(graph)
                Shelf.History -> HistoryShelf(graph, player)
            }
        }
    }
}
