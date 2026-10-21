package com.localdoc.ai.net

/**
 * Публичные суффиксы, на которые сайт не вправе ставить cookie для «всего домена»: TLD, вторые уровни
 * вида «co.uk» / «com.br» и популярные хостинги пользовательских сайтов («github.io», «blogspot.com»…).
 * Это компактная замена полному списку Public Suffix List: безопаснее отвергнуть лишнее, чем разослать сессию соседям.
 */
object PublicSuffixes {

    private val SECOND_LEVEL_MARKERS = setOf("co", "com", "org", "net", "gov", "edu", "ac", "mil", "ne", "or", "go", "gob", "nom", "pp", "int")

    private val HOSTING = setOf(
        "github.io", "gitlab.io", "pages.dev", "workers.dev", "netlify.app", "vercel.app", "herokuapp.com", "blogspot.com", "blogspot.ru",
        "wordpress.com", "tumblr.com", "weebly.com", "wixsite.com", "narod.ru", "ucoz.ru", "ucoz.com", "ucoz.net", "at.ua", "3dn.ru",
        "livejournal.com", "dreamwidth.org", "fandom.com", "web.app", "firebaseapp.com", "appspot.com", "azurewebsites.net",
        "cloudfront.net", "amazonaws.com", "s3.amazonaws.com", "repl.co", "glitch.me", "000webhostapp.com", "neocities.org",
        "msk.ru", "spb.ru", "nsk.ru", "com.ru", "org.ru", "net.ru", "pp.ru", "kiev.ua", "com.ua", "org.ua", "net.ua", "in.ua"
    )

    fun isPublicSuffix(domain: String): Boolean {
        val d = domain.trim().trim('.').lowercase()
        val labels = d.split('.')
        if (d.isEmpty() || labels.size < 2) return true
        if (d in HOSTING) return true
        // «co.uk», «com.au», «org.br»: второй уровень под двухбуквенным национальным доменом
        return labels.size == 2 && labels[0] in SECOND_LEVEL_MARKERS && labels[1].length == 2
    }
}
