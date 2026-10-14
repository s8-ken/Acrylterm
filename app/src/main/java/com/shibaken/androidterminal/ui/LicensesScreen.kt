package com.shibaken.androidterminal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.LibraryDefaults
import com.shibaken.androidterminal.R

/** Open-source licenses of everything bundled in the app (AboutLibraries + config/ definitions). */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    VeilBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VeilIconButton(R.drawable.veil_back, "戻る", onBack)
                Text("ライセンス", fontSize = 23.sp, fontWeight = FontWeight.Medium)
            }
            LibrariesContainer(
                Modifier.weight(1f).fillMaxWidth(),
                colors = VeilLibraryColors(),
            )
        }
    }
}

@Composable
private fun VeilLibraryColors() = LibraryDefaults.libraryColors(
    backgroundColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.onSurface,
    badgeBackgroundColor = CyanVeil.Raised,
    badgeContentColor = CyanVeil.Cyan,
    dialogConfirmButtonColor = CyanVeil.Cyan,
)
