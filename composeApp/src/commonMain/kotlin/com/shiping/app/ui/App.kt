package com.shiping.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shiping.shared.SHARED_VERSION

// M0 统一入口占位：M3 起以桌面版 UI 为底迁入 commonMain
@Composable
fun App() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "ShijieCmp", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "KMP + CMP 骨架已就绪", style = MaterialTheme.typography.bodyMedium)
                Text(text = "shared = com.shiping.shared v$SHARED_VERSION", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
