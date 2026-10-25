// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.secretarrow.noidmobile.ui.NoidTheme
import org.secretarrow.noidmobile.ui.component.BottomNav
import org.secretarrow.noidmobile.ui.screen.DashboardScreen
import org.secretarrow.noidmobile.ui.screen.MinerScreen
import org.secretarrow.noidmobile.ui.screen.SettingsScreen
import org.secretarrow.noidmobile.ui.screen.WalletScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoidTheme {
                NoidAppRoot()
            }
        }
    }
}

@Composable
private fun NoidAppRoot() {
    val nav = rememberNavController()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomNav(nav) },
    ) { inner ->
        // Outer Scaffold owns the bottomBar (NavigationBar) and consumes the
        // system status bar inset via its `inner` PaddingValues. Each screen
        // has its own Scaffold with a TopAppBar that picks up the status bar
        // inset from the unconsumed top (the outer Scaffold has no topBar).
        //
        // We apply the outer `inner` padding AND consume those insets so the
        // inner Scaffolds don't double-apply them.
        NavHost(
            navController = nav,
            startDestination = "dashboard",
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner),
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(220)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(220)) },
        ) {
            composable("dashboard") {
                DashboardScreen(onNavigateWallet = { nav.navigate("wallet") })
            }
            composable("miner") { MinerScreen() }
            composable("wallet") { WalletScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
