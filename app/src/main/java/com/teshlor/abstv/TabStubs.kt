package com.teshlor.abstv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text

// Placeholders for the tabs that come in later milestones. They are unreachable while Tab.enabled is false;
// each is replaced by its real screen (LibraryTab, SeriesTab, ...) when that tab is built.

@Composable private fun Soon(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("$name: coming soon", color = LocalAbsColors.current.muted) }
}

@Composable fun LibraryTab() = Soon("Library")
