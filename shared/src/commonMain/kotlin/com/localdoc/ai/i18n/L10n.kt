package com.localdoc.ai.i18n

import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Локализованные строки для не-UI слоёв (домен, движок, сервисы).
 * Обёртка над compose-resources: JVM-тесты доменной логики не имеют android.content.res.Resources,
 * поэтому в commonTest устанавливается тестовый резолвер (TestStrings), а в приложении строки
 * идут штатно через getString (язык интерфейса, локализованные словари).
 */
object L10n {
    /** Тестовый резолвер: ключ ресурса («string:имя») и аргументы → готовая строка. */
    internal var testResolver: (suspend (StringResource, List<Any>) -> String)? = null

    suspend fun get(resource: StringResource, vararg args: Any): String {
        val override = testResolver
        return override?.invoke(resource, args.toList()) ?: getString(resource, *args)
    }
}
