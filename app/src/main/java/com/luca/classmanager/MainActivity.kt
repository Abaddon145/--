package com.luca.classmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luca.classmanager.ui.navigation.LucaAppRoot
import com.luca.classmanager.ui.theme.LucaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LucaApplication).container
        setContent {
            val themeMode by container.settingsRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = com.luca.classmanager.domain.model.ThemeMode.SYSTEM,
            )
            LucaTheme(themeMode = themeMode) {
                LucaAppRoot(container)
            }
        }
    }
}
