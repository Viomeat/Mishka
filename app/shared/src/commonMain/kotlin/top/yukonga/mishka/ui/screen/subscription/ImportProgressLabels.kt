package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.runtime.Composable
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.subscription_downloading
import mishka.shared.generated.resources.subscription_prefetching
import mishka.shared.generated.resources.subscription_updating_progress
import mishka.shared.generated.resources.subscription_validating
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.data.repository.ImportProgress
import top.yukonga.mishka.data.repository.ImportStep

/**
 * 把 data 层的结构化 [ImportProgress] 映射为本地化文案。
 * 本地化归 UI 层：data 只发 [ImportStep] 语义步骤，不依赖 Compose Resources。
 */
@Composable
fun importStepLabel(p: ImportProgress): String = when (p.step) {
    ImportStep.Downloading -> stringResource(Res.string.subscription_downloading)
    ImportStep.Prefetching ->
        if (p.providerName.isNotEmpty() && p.total > 0) {
            stringResource(Res.string.subscription_updating_progress, p.providerName, p.current + 1, p.total)
        } else {
            stringResource(Res.string.subscription_prefetching)
        }

    ImportStep.Validating -> stringResource(Res.string.subscription_validating)
    ImportStep.Other -> p.rawLabel
}

/** 非 Composable 版本，供 ViewModel 协程内构造进度文案。 */
suspend fun importStepLabelAsync(p: ImportProgress): String = when (p.step) {
    ImportStep.Downloading -> getString(Res.string.subscription_downloading)
    ImportStep.Prefetching ->
        if (p.providerName.isNotEmpty() && p.total > 0) {
            getString(Res.string.subscription_updating_progress, p.providerName, p.current + 1, p.total)
        } else {
            getString(Res.string.subscription_prefetching)
        }

    ImportStep.Validating -> getString(Res.string.subscription_validating)
    ImportStep.Other -> p.rawLabel
}
