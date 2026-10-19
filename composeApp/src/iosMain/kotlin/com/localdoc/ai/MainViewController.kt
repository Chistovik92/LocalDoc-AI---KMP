package com.localdoc.ai

import androidx.compose.ui.window.ComposeUIViewController
import com.localdoc.ai.ui.App
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    App()
}
