package top.yukonga.mishka.ui.theme

import androidx.compose.runtime.Composable
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.settings_theme_accent_blue
import mishka.shared.generated.resources.settings_theme_accent_default
import mishka.shared.generated.resources.settings_theme_accent_green
import mishka.shared.generated.resources.settings_theme_accent_orange
import mishka.shared.generated.resources.settings_theme_accent_pink
import mishka.shared.generated.resources.settings_theme_accent_purple
import mishka.shared.generated.resources.settings_theme_accent_red
import mishka.shared.generated.resources.settings_theme_accent_teal
import mishka.shared.generated.resources.settings_theme_accent_yellow
import mishka.shared.generated.resources.settings_theme_bottom_bar_icon_and_text
import mishka.shared.generated.resources.settings_theme_bottom_bar_icon_only
import mishka.shared.generated.resources.settings_theme_dark
import mishka.shared.generated.resources.settings_theme_floating_bottom_bar_style_ios_like
import mishka.shared.generated.resources.settings_theme_floating_bottom_bar_style_miuix
import mishka.shared.generated.resources.settings_theme_light
import mishka.shared.generated.resources.settings_theme_palette_content
import mishka.shared.generated.resources.settings_theme_palette_expressive
import mishka.shared.generated.resources.settings_theme_palette_fidelity
import mishka.shared.generated.resources.settings_theme_palette_fruit_salad
import mishka.shared.generated.resources.settings_theme_palette_monochrome
import mishka.shared.generated.resources.settings_theme_palette_neutral
import mishka.shared.generated.resources.settings_theme_palette_rainbow
import mishka.shared.generated.resources.settings_theme_palette_tonal_spot
import mishka.shared.generated.resources.settings_theme_palette_vibrant
import mishka.shared.generated.resources.settings_theme_system
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

// 主题枚举 → 用户可见名称的唯一映射；设置入口摘要与主题设置页共用，避免两处 when 漂移

@Composable
fun themeColorModeLabel(colorMode: Int): String = stringResource(
    when (colorMode) {
        1 -> Res.string.settings_theme_light
        2 -> Res.string.settings_theme_dark
        else -> Res.string.settings_theme_system
    },
)

@Composable
fun ThemeAccentColor.label(): String = stringResource(
    when (this) {
        ThemeAccentColor.Default -> Res.string.settings_theme_accent_default
        ThemeAccentColor.Blue -> Res.string.settings_theme_accent_blue
        ThemeAccentColor.Purple -> Res.string.settings_theme_accent_purple
        ThemeAccentColor.Pink -> Res.string.settings_theme_accent_pink
        ThemeAccentColor.Red -> Res.string.settings_theme_accent_red
        ThemeAccentColor.Orange -> Res.string.settings_theme_accent_orange
        ThemeAccentColor.Yellow -> Res.string.settings_theme_accent_yellow
        ThemeAccentColor.Green -> Res.string.settings_theme_accent_green
        ThemeAccentColor.Teal -> Res.string.settings_theme_accent_teal
    },
)

@Composable
fun ThemePaletteStyle.label(): String = stringResource(
    when (this) {
        ThemePaletteStyle.Neutral -> Res.string.settings_theme_palette_neutral
        ThemePaletteStyle.Vibrant -> Res.string.settings_theme_palette_vibrant
        ThemePaletteStyle.Expressive -> Res.string.settings_theme_palette_expressive
        ThemePaletteStyle.Rainbow -> Res.string.settings_theme_palette_rainbow
        ThemePaletteStyle.FruitSalad -> Res.string.settings_theme_palette_fruit_salad
        ThemePaletteStyle.Monochrome -> Res.string.settings_theme_palette_monochrome
        ThemePaletteStyle.Fidelity -> Res.string.settings_theme_palette_fidelity
        ThemePaletteStyle.Content -> Res.string.settings_theme_palette_content
        else -> Res.string.settings_theme_palette_tonal_spot
    },
)

@Composable
fun FloatingBottomBarStyle.label(): String = stringResource(
    when (this) {
        FloatingBottomBarStyle.Miuix -> Res.string.settings_theme_floating_bottom_bar_style_miuix
        FloatingBottomBarStyle.IosLike -> Res.string.settings_theme_floating_bottom_bar_style_ios_like
    },
)

@Composable
fun BottomBarMode.label(): String = stringResource(
    when (this) {
        BottomBarMode.IconAndText -> Res.string.settings_theme_bottom_bar_icon_and_text
        BottomBarMode.IconOnly -> Res.string.settings_theme_bottom_bar_icon_only
    },
)
