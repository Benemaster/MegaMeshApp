package com.example.megameshapp

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.megameshapp.ui.screens.ChatScreen
import com.example.megameshapp.ui.screens.DeviceSettingsScreen
import com.example.megameshapp.ui.screens.MapWeatherScreen
import com.example.megameshapp.ui.theme.*
import com.example.megameshapp.viewmodel.MeshViewModel
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MegaMeshAppTheme {
                MegaMeshApp()
            }
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Map : Screen("map", "Karte", Icons.Default.Map)
    data object Chat : Screen("chat", "Chat", Icons.Default.Forum)
    data object Settings : Screen("settings", "Gerät", Icons.Default.Settings)
}

@Composable
fun MegaMeshApp(meshViewModel: MeshViewModel = viewModel()) {
    val screens = listOf(Screen.Map, Screen.Chat, Screen.Settings)
    var selectedScreen by remember { mutableStateOf<Screen>(Screen.Chat) }
    val snackbarHostState = remember { SnackbarHostState() }
    val view = LocalView.current

    // Collect user feedback and show as Snackbar with haptic
    LaunchedEffect(meshViewModel) {
        meshViewModel.userFeedback.collectLatest { message ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DarkSurfaceVariant,
                    contentColor = TextPrimary,
                    actionColor = GreenPrimary
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                screen.icon,
                                contentDescription = screen.title,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                screen.title,
                                fontSize = 12.sp,
                                fontWeight = if (selectedScreen == screen) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        selected = selectedScreen == screen,
                        onClick = { selectedScreen = screen },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GreenPrimary,
                            selectedTextColor = GreenPrimary,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = GreenPrimary.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedScreen) {
                Screen.Map -> MapWeatherScreen(meshViewModel)
                Screen.Chat -> ChatScreen(meshViewModel)
                Screen.Settings -> DeviceSettingsScreen(meshViewModel)
            }
        }
    }
}

