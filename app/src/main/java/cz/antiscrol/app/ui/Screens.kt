package cz.antiscrol.app.ui

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cz.antiscrol.app.web.Section
import cz.antiscrol.app.web.WebController

private val Teal = Color(0xFF1F6F5C)
private val TealLight = Color(0xFF7FD6BF)

@Composable
fun AntiScrolTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = TealLight, secondary = TealLight)
    } else {
        lightColorScheme(primary = Teal, secondary = Teal)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun MainScreen(
    controller: WebController,
    snackbarHostState: SnackbarHostState,
    settingsVisible: Boolean,
    onOpenSettings: () -> Unit,
    settingsContent: @Composable () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = !settingsVisible && controller.section == Section.STORIES,
                    onClick = { controller.openStories() },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Stories") },
                )
                NavigationBarItem(
                    selected = !settingsVisible && controller.section == Section.MESSAGES,
                    onClick = { controller.openMessages() },
                    icon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    label = { Text("Zprávy") },
                )
                NavigationBarItem(
                    selected = !settingsVisible && controller.section == Section.ADD_STORY,
                    onClick = { controller.addStory() },
                    icon = { Icon(Icons.Filled.AddCircle, contentDescription = null) },
                    label = { Text("+ Story") },
                )
                NavigationBarItem(
                    selected = settingsVisible,
                    onClick = onOpenSettings,
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Nastavení") },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // Keeps the message field above the keyboard.
                .imePadding(),
        ) {
            // Always stays in the composition; the other screens are drawn on top of it.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    controller.webView.apply {
                        (parent as? ViewGroup)?.removeView(this)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
            )
            if (controller.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { controller.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
            if (controller.blockedVisible) {
                BlockedScreen(onBackToStories = { controller.dismissBlockedToStories() })
            }
            if (settingsVisible) {
                settingsContent()
            }
        }
    }
}

@Composable
fun BlockedScreen(onBackToStories: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Tohle je zablokované 🙂",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Feed, Reels ani Explore tady nenajdeš. Zůstávají stories, zprávy a to, co ti pošlou kamarádi.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onBackToStories) {
                Text("Zpět na Stories")
            }
        }
    }
}

@Composable
fun SettingsScreen(
    profilesEnabled: Boolean,
    onProfilesChanged: (Boolean) -> Unit,
    rulesInfo: String,
    appVersion: String,
    onRefreshRules: () -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                }
                Text("Nastavení", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProfilesChanged(!profilesEnabled) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Povolit profily", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Půjde otevřít profil kamaráda a příspěvky z jeho mřížky. Feed, Reels a Explore zůstávají zablokované.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.padding(8.dp))
                Switch(checked = profilesEnabled, onCheckedChange = onProfilesChanged)
            }
            HorizontalDivider()

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Pravidla skrývání", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    rulesInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onRefreshRules) {
                    Text("Zkusit stáhnout novější")
                }
            }
            HorizontalDivider()

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Účet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Přihlášení drží jen prohlížeč uvnitř aplikace. Heslo aplikace nevidí a nic neposílá na vlastní server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onSignOut) {
                    Text("Odhlásit a smazat data")
                }
            }
            HorizontalDivider()

            Text(
                "AntiScrol $appVersion",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
