package io.theficos.ereader.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.theficos.quire.R

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    LIBRARY(R.string.common_library_2, Icons.Outlined.LibraryBooks),
    CATALOG(R.string.common_catalog, Icons.Outlined.Storefront),
    SETTINGS(R.string.common_settings, Icons.Outlined.Settings),
}

@Composable
fun MainScaffold(
    initial: Tab = Tab.LIBRARY,
    content: @Composable (Tab, PaddingValues) -> Unit,
) {
    // rememberSaveable so the selected tab survives a navigation push to a
    // detail screen + popBackStack: returning from catalog-detail should land
    // back on the Catalog tab, not reset to the default LIBRARY.
    var current by rememberSaveable { mutableStateOf(initial) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.label)) },
                        label = { Text(stringResource(tab.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        content(current, padding)
    }
}
