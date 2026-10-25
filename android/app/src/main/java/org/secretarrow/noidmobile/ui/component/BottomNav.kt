// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.ui.component

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import org.secretarrow.noidmobile.ui.NoidBlack
import org.secretarrow.noidmobile.ui.NoidGreen

private data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val NAV_ITEMS = listOf(
    NavItem("dashboard", "DASH", Icons.Filled.Dashboard),
    NavItem("miner", "MINER", Icons.Filled.PlayArrow),
    NavItem("wallet", "WALLET", Icons.Filled.AccountBalanceWallet),
    NavItem("settings", "CONFIG", Icons.Filled.Settings),
)

@Composable
fun BottomNav(nav: NavController) {
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    NavigationBar(
        containerColor = NoidBlack,
        contentColor = NoidGreen,
    ) {
        for (item in NAV_ITEMS) {
            NavigationBarItem(
                selected = current == item.route,
                onClick = {
                    nav.navigate(item.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NoidGreen,
                    selectedTextColor = NoidGreen,
                    unselectedIconColor = Color(0xFF555555),
                    unselectedTextColor = Color(0xFF555555),
                    indicatorColor = NoidBlack,
                ),
            )
        }
    }
}
