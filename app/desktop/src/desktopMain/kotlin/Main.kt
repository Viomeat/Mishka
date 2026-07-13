import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import top.yukonga.mishka.App
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.ui.theme.readThemeConfig

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Mishka",
        state = rememberWindowState(width = 400.dp, height = 800.dp),
    ) {
        val storage = remember { PlatformStorage() }
        var themeConfig by remember { mutableStateOf(readThemeConfig(storage)) }
        App(
            themeConfig = themeConfig,
            onThemeConfigChange = { themeConfig = it },
            storage = storage,
        )
    }
}
