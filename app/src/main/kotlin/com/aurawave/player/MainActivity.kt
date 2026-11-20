package com.aurawave.player

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.aurawave.player.playback.PlayerConnection
import com.aurawave.player.ui.AppRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var player: PlayerConnection

    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denied is fine: music still plays, just without the notification controls.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val graph = (application as AuraApp).graph
        player = PlayerConnection(applicationContext)
        setContent { AppRoot(graph, player) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        player.connect()
    }

    override fun onStop() {
        player.disconnect()
        // Leaving the app: bring the backup in Documents/AuraWave up to date, if anything changed.
        val graph = (application as AuraApp).graph
        graph.scope.launch { graph.backup.autoSave() }
        super.onStop()
    }
}
