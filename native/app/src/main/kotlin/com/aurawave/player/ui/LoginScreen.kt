package com.aurawave.player.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.aurawave.player.AppGraph
import com.aurawave.player.data.Account
import kotlinx.coroutines.launch

/**
 * Google's own sign-in page, inside the app. When it lands on YouTube Music, the login
 * cookies are read from this app's WebView and kept on the phone.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(graph: AppGraph, onBack: () -> Unit) {
    val actions = LocalSongActions.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(onBack, "Sign in to YouTube Music")
        if (loading || finishing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuses sign-in from pages that announce themselves as an embedded WebView.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                if (finishing || url == null || !url.startsWith("https://music.youtube.com")) return
                                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return
                                if (!cookie.contains("SAPISID")) return
                                finishing = true
                                scope.launch {
                                    val problem = graph.account.signIn(cookie)
                                    finishing = false
                                    if (problem == null) {
                                        actions.say("Signed in. Syncing your library…")
                                        onBack()
                                    } else {
                                        actions.say(problem)
                                    }
                                }
                            }
                        }
                        loadUrl(Account.SIGN_IN_URL)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
