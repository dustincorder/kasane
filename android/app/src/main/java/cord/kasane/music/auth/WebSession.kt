package cord.kasane.music.auth

import org.json.JSONObject
import java.net.URI

data class CapturedWebSession(
    val cookie: String,
    val visitorData: String?,
    val dataSyncId: String?,
    val authUser: Int,
    val pageId: String?,
)

data class AccountChannel(
    val title: String,
    val subtitle: String?,
    val pageId: String?,
    val dataSyncId: String?,
    val avatar: String?,
    val selected: Boolean,
)

object WebSessionParser {
    private val authCookieNames = setOf(
        "SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "APISID", "SID", "HSID", "SSID",
        "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO", "__Secure-1PSIDTS", "__Secure-3PSIDTS",
        "__Secure-1PSIDCC", "__Secure-3PSIDCC", "LSID", "__Host-1PLSID", "__Host-3PLSID",
    )

    fun capture(cookieHeader: String?, configJson: String?): CapturedWebSession? {
        val config = runCatching { JSONObject(configJson ?: return null) }.getOrNull() ?: return null
        if (!config.optBoolean("loggedIn")) return null
        val visitor = config.optString("visitorData").takeIf { it.matches(Regex("[A-Za-z0-9._=-]{1,4096}")) } ?: return null
        val authUser = config.optString("authUser", "0").toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val sync = config.optString("dataSyncId").takeIf { it.isNotBlank() && it.length <= 2048 && it.all { ch -> ch.code in 32..126 } }
        val pageId = config.optString("pageId").takeIf(String::isNotBlank)
        return captureCookies(cookieHeader, visitor, sync, authUser, pageId)
    }

    /** Captures only YouTube Music scoped auth cookies before its home page/ytcfg finishes loading. */
    fun captureCookies(
        cookieHeader: String?,
        visitorData: String? = null,
        dataSyncId: String? = null,
        authUser: Int = 0,
        pageId: String? = null,
    ): CapturedWebSession? {
        val cookie = cookieHeader.orEmpty().split(';').mapNotNull { raw ->
            val name = raw.trim().substringBefore('=')
            raw.trim().takeIf { name in authCookieNames && it.contains('=') }
        }.distinctBy { it.substringBefore('=') }.joinToString("; ")
        val cookieNames = cookie.split(';').map { it.trim().substringBefore('=') }
        if (cookie.isBlank() || cookie.length > 24_000 ||
            cookieNames.none { it == "SAPISID" || it == "__Secure-3PAPISID" || it == "__Secure-1PAPISID" } || authUser < 0) return null
        if (visitorData != null && !visitorData.matches(Regex("[A-Za-z0-9._=-]{1,4096}"))) return null
        val visitor = visitorData
        val sync = dataSyncId?.takeIf { it.isNotBlank() && it.length <= 2048 && it.all { ch -> ch.code in 32..126 } }
        val safePageId = pageId?.takeIf { it.isNotBlank() && it.length <= 256 && it.all { ch -> ch.code in 32..126 } }
        return CapturedWebSession(cookie, visitor, sync, authUser, safePageId)
    }

    fun isTrustedHttpsUrl(value: String?): Boolean {
        val uri = runCatching { URI(value ?: return false) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in TRUSTED_HOSTS
    }

    fun isGoogleAccountUrl(value: String?): Boolean {
        val uri = runCatching { URI(value ?: return false) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase() in GOOGLE_ACCOUNT_HOSTS
    }

    fun parseChannels(root: JSONObject): List<AccountChannel> {
        val found = linkedMapOf<String, AccountChannel>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val renderer = value.optJSONObject("accountItemRenderer")
                        ?: value.optJSONObject("accountItem")
                    if (renderer != null) {
                        val title = renderer.optJSONObject("accountName")?.let(::text).orEmpty()
                            .ifBlank { renderer.optString("accountName") }
                        val subtitle = renderer.optJSONObject("accountByline")?.let(::text)
                            ?.takeIf(String::isNotBlank)
                        val endpoint = renderer.optJSONObject("serviceEndpoint")?.optJSONObject("selectActiveIdentityEndpoint")
                        val supported = endpoint?.optJSONArray("supportedTokens")
                        var pageId = renderer.optString("pageId").takeIf(String::isNotBlank)
                        var sync: String? = renderer.optString("dataSyncId").takeIf(String::isNotBlank)
                        if (supported != null) for (index in 0 until supported.length()) {
                            val token = supported.optJSONObject(index) ?: continue
                            token.optJSONObject("pageIdToken")?.optString("pageId")
                                ?.takeIf(String::isNotBlank)?.let { pageId = it }
                            token.optJSONObject("datasyncIdToken")?.optString("datasyncIdToken")
                                ?.takeIf(String::isNotBlank)?.let { sync = it }
                        }
                        val image = renderer.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url")
                            ?.takeIf(String::isNotBlank)
                        if (title.isNotBlank()) {
                            val key = pageId ?: sync ?: title
                            found[key] = AccountChannel(title, subtitle, pageId, sync, image,
                                renderer.optBoolean("isSelected"))
                        }
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()))
                }
                is org.json.JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(root)
        return found.values.toList()
    }

    private fun text(value: JSONObject): String = buildString {
        val runs = value.optJSONArray("runs") ?: return value.optString("simpleText")
        for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
    }

    // Regional domains published by Google at https://www.google.com/supported_domains.
    // Expand only to exact root/www/accounts hosts; never trust arbitrary google.* subdomains.
    private val GOOGLE_REGIONAL_DOMAINS = """
        google.com google.ad google.ae google.com.af google.com.ag google.al google.am google.co.ao
        google.com.ar google.as google.at google.com.au google.az google.ba google.com.bd google.be
        google.bf google.bg google.com.bh google.bi google.bj google.com.bn google.com.bo google.com.br
        google.bs google.bt google.co.bw google.by google.com.bz google.ca google.cd google.cf google.cg
        google.ch google.ci google.co.ck google.cl google.cm google.cn google.com.co google.co.cr google.com.cu
        google.cv google.com.cy google.cz google.de google.dj google.dk google.dm google.com.do google.dz
        google.com.ec google.ee google.com.eg google.es google.com.et google.fi google.com.fj google.fm
        google.fr google.ga google.ge google.gg google.com.gh google.com.gi google.gl google.gm google.gr
        google.com.gt google.gy google.com.hk google.hn google.hr google.ht google.hu google.co.id google.ie
        google.co.il google.im google.co.in google.iq google.is google.it google.je google.com.jm google.jo
        google.co.jp google.co.ke google.com.kh google.ki google.kg google.co.kr google.com.kw google.kz
        google.la google.com.lb google.li google.lk google.co.ls google.lt google.lu google.lv google.com.ly
        google.co.ma google.md google.me google.mg google.mk google.ml google.com.mm google.mn google.com.mt
        google.mu google.mv google.mw google.com.mx google.com.my google.co.mz google.com.na google.com.ng
        google.com.ni google.ne google.nl google.no google.com.np google.nr google.nu google.co.nz google.com.om
        google.com.pa google.com.pe google.com.pg google.com.ph google.com.pk google.pl google.pn google.com.pr
        google.ps google.pt google.com.py google.com.qa google.ro google.ru google.rw google.com.sa google.com.sb
        google.sc google.se google.com.sg google.sh google.si google.sk google.com.sl google.sn google.so google.sm
        google.sr google.st google.com.sv google.td google.tg google.co.th google.com.tj google.tl google.tm google.tn
        google.to google.com.tr google.tt google.com.tw google.co.tz google.com.ua google.co.ug google.co.uk
        google.com.uy google.co.uz google.com.vc google.co.ve google.co.vi google.com.vn google.vu google.ws google.rs
        google.co.za google.co.zm google.co.zw google.cat
    """.trimIndent().split(Regex("\\s+")).toSet()

    private val GOOGLE_REGIONAL_HOSTS = buildSet {
        GOOGLE_REGIONAL_DOMAINS.forEach { domain ->
            add(domain)
            add("www.$domain")
            add("accounts.$domain")
        }
    }

    private val TRUSTED_HOSTS = GOOGLE_REGIONAL_HOSTS + setOf(
        "accounts.youtube.com", "myaccount.google.com", "consent.google.com", "consent.youtube.com",
        "ogs.google.com", "gds.google.com", "support.google.com", "youtube.com", "www.youtube.com",
        "m.youtube.com", "music.youtube.com",
    )
    private val GOOGLE_ACCOUNT_HOSTS = GOOGLE_REGIONAL_DOMAINS.mapTo(mutableSetOf()) { "accounts.$it" }
}
