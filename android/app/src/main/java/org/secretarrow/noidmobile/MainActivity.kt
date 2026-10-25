// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
                NoidApp()
            }
        }
    }
}

@Composable
private fun NoidApp() {
    val nav = rememberNavController()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Black,
        bottomBar = { BottomNav(nav) },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = "dashboard",
            modifier = Modifier.padding(inner),
        ) {
            composable("dashboard") { DashboardScreen() }
            composable("miner") { MinerScreen() }
            composable("wallet") { WalletScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
