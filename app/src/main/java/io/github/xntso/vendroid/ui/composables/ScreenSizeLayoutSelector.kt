package io.github.xntso.vendroid.ui.composables

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowSizeClass


@Composable
fun ScreenSizeLayoutSelector(
    modifier: Modifier = Modifier,
    normal: @Composable BoxScope.() -> Unit,
    compact: @Composable BoxScope.() -> Unit,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
) {
    val isCompact by remember {
        derivedStateOf {
            windowSizeClass.windowHeightSizeClass == WindowHeightSizeClass.COMPACT
        }
    }

    AnimatedContent(
        targetState = isCompact,
        label = "portrait/landscape switch"
    ) {
        Box(modifier) {
            if (it) {
                compact()
            } else {
                normal()
            }
        }
    }
}
