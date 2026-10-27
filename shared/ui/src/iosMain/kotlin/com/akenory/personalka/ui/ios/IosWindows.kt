package com.akenory.personalka.ui.ios

import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.popoverPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Выполнить на главном потоке: окна UIKit открываются только с него. */
internal fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

/** Показать системное окно поверх приложения (над уже открытым окном, если оно есть). */
internal fun present(controller: UIViewController) = onMain {
    var top = rootController() ?: return@onMain
    while (true) top = top.presentedViewController ?: break
    // На iPad «Поделиться» — всплывающее окно, ему нужна точка привязки; на iPhone не мешает.
    (controller as? UIActivityViewController)?.popoverPresentationController?.sourceView = top.view
    top.presentViewController(controller, animated = true, completion = null)
}

private fun rootController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes
    val window = scenes.firstNotNullOfOrNull { scene -> (scene as? UIWindowScene)?.windows?.firstOrNull { (it as UIWindow).isKeyWindow() } as? UIWindow }
        ?: scenes.firstNotNullOfOrNull { (it as? UIWindowScene)?.windows?.firstOrNull() as? UIWindow }
    return window?.rootViewController
}
