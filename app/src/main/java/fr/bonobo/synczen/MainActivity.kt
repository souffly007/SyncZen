package fr.bonobo.synczen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fr.bonobo.synczen.ui.SyncScreen
import fr.bonobo.synczen.ui.SyncViewModel
import fr.bonobo.synczen.ui.theme.SyncZenTheme
import fr.bonobo.synczen.ui.theme.SyncThemeMode

class MainActivity : ComponentActivity() {

    private val viewModel: SyncViewModel by viewModels()
    private var themeMode by mutableStateOf(SyncThemeMode.LIGHT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("synczen_preferences", MODE_PRIVATE)
        themeMode = runCatching {
            SyncThemeMode.valueOf(preferences.getString("theme_mode", SyncThemeMode.LIGHT.name)!!)
        }.getOrDefault(SyncThemeMode.LIGHT)
        setContent {
            SyncZenTheme(mode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncScreen(
                        viewModel = viewModel,
                        themeMode = themeMode,
                        onThemeChanged = { selected ->
                            themeMode = selected
                            preferences.edit().putString("theme_mode", selected.name).apply()
                        }
                    )
                }
            }
        }
    }
}
