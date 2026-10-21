package com.localdoc.ai.platform

import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/** Верхний показанный экран — от него презентуются системные окна выбора и сохранения файлов. */
internal fun topViewController(): UIViewController? {
    val scene = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull()
    val windows = scene?.windows?.filterIsInstance<UIWindow>().orEmpty()
    var controller = (windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull())?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}
