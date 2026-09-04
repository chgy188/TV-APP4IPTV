package com.example.composedtv.data.remote

/**
 * worker4iptv 后端数据模型
 */

data class ApiSource(
    val id: String,
    val name: String,
    val url: String,
    val local: Boolean = false,
    val public: Boolean = false,
    val ownerId: String? = null,
    val ownerName: String? = null
)

data class ApiChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val status: String,
    val checkedAt: Long,
    val checkError: String,
    val lastStatus: Int,
    val country: String,
    /** 频道归属国（ISO 2 字母代码，来自 iptv-org，前端显示用） */
    val countryAttr: String = "",
    /** 频道语言（ISO 639-3 代码数组，来自 iptv-org，前端显示用） */
    val langs: List<String> = emptyList(),
    /** 源站要求的自定义请求头：User-Agent（防盗链常用） */
    val ua: String = "",
    /** 源站要求的自定义请求头：Referer（服务端字段名 rf，防盗链常用） */
    val rf: String = ""
)

data class AuthResult(
    val ok: Boolean,
    val token: String?,
    val user: ApiUser?,
    val message: String?
)

data class ApiUser(
    val id: String,
    val username: String,
    val role: String?,
    val needsDefaultSource: Boolean = false
)

data class ApiFavorite(
    val id: String,
    val url: String,
    val country: String,
    val sourceId: String?,
    val name: String,
    val logo: String?,
    val status: String?,
    /** 源站要求的自定义请求头：User-Agent（防盗链常用） */
    val ua: String = "",
    /** 源站要求的自定义请求头：Referer（服务端字段名 rf，防盗链常用） */
    val rf: String = ""
)

data class ApiSquareItem(
    val id: String,
    val url: String,
    val name: String,
    val ownerId: String?,
    val ownerName: String?,
    val public: Boolean = false
)

/** 存储的已登录用户会话 */
data class StoredUser(
    val username: String,
    val token: String,
    val userId: String = "",
    val role: String? = null
)

/**
 * 国家代码（ISO 3166-1 alpha-2）和语言代码（ISO 639-3）到中文的映射。
 * 参考 worker4iptv 项目的 COUNTRY_CN / LANG_CN 字典。
 */
object CountryLangMapper {

    private val COUNTRY_CN = mapOf(
        "CN" to "中国", "HK" to "中国香港", "MO" to "中国澳门", "TW" to "中国台湾",
        "US" to "美国", "JP" to "日本", "KR" to "韩国", "KP" to "朝鲜", "SG" to "新加坡",
        "MY" to "马来西亚", "TH" to "泰国", "VN" to "越南", "PH" to "菲律宾",
        "ID" to "印度尼西亚", "IN" to "印度", "GB" to "英国", "FR" to "法国", "DE" to "德国",
        "IT" to "意大利", "ES" to "西班牙", "PT" to "葡萄牙", "NL" to "荷兰", "RU" to "俄罗斯",
        "UA" to "乌克兰", "TR" to "土耳其", "SA" to "沙特阿拉伯", "AE" to "阿联酋",
        "CA" to "加拿大", "MX" to "墨西哥", "BR" to "巴西", "AR" to "阿根廷",
        "AU" to "澳大利亚", "NZ" to "新西兰", "EG" to "埃及", "ZA" to "南非",
        "IL" to "以色列", "IR" to "伊朗", "PK" to "巴基斯坦", "BD" to "孟加拉国",
        "LK" to "斯里兰卡", "KZ" to "哈萨克斯坦", "GE" to "格鲁吉亚",
        "SK" to "斯洛伐克", "CZ" to "捷克", "PL" to "波兰", "HU" to "匈牙利",
        "RO" to "罗马尼亚", "BG" to "保加利亚", "HR" to "克罗地亚", "SI" to "斯洛文尼亚",
        "RS" to "塞尔维亚", "MK" to "北马其顿", "BA" to "波斯尼亚和黑塞哥维那",
        "ME" to "黑山", "LT" to "立陶宛", "LV" to "拉脱维亚", "EE" to "爱沙尼亚",
        "BY" to "白俄罗斯", "MD" to "摩尔多瓦", "AL" to "阿尔巴尼亚", "XK" to "科索沃",
        "SE" to "瑞典", "NO" to "挪威", "DK" to "丹麦", "FI" to "芬兰", "IS" to "冰岛",
        "AT" to "奥地利", "CH" to "瑞士", "BE" to "比利时", "GR" to "希腊", "IE" to "爱尔兰",
        "LU" to "卢森堡", "MT" to "马耳他", "CY" to "塞浦路斯",
        "CR" to "哥斯达黎加", "PA" to "巴拿马", "GT" to "危地马拉", "CU" to "古巴",
        "DO" to "多米尼加", "JM" to "牙买加", "HT" to "海地", "NI" to "尼加拉瓜",
        "HN" to "洪都拉斯", "SV" to "萨尔瓦多", "PR" to "波多黎各", "BS" to "巴哈马",
        "TT" to "特立尼达和多巴哥",
        "CL" to "智利", "CO" to "哥伦比亚", "PE" to "秘鲁", "VE" to "委内瑞拉",
        "EC" to "厄瓜多尔", "UY" to "乌拉圭", "BO" to "玻利维亚", "PY" to "巴拉圭",
        "SY" to "叙利亚", "IQ" to "伊拉克", "JO" to "约旦", "LB" to "黎巴嫩",
        "KW" to "科威特", "QA" to "卡塔尔", "OM" to "阿曼", "YE" to "也门",
        "BH" to "巴林", "AZ" to "阿塞拜疆", "AM" to "亚美尼亚", "TM" to "土库曼斯坦",
        "TJ" to "塔吉克斯坦", "KG" to "吉尔吉斯斯坦", "UZ" to "乌兹别克斯坦",
        "AF" to "阿富汗", "KH" to "柬埔寨", "LA" to "老挝", "MM" to "缅甸",
        "BN" to "文莱", "NP" to "尼泊尔", "BT" to "不丹", "MV" to "马尔代夫",
        "MN" to "蒙古", "NG" to "尼日利亚", "KE" to "肯尼亚", "MA" to "摩洛哥",
        "TN" to "突尼斯", "DZ" to "阿尔及利亚", "GH" to "加纳", "ET" to "埃塞俄比亚",
        "TZ" to "坦桑尼亚", "UG" to "乌干达", "CM" to "喀麦隆", "SN" to "塞内加尔",
        "CI" to "科特迪瓦", "ZW" to "津巴布韦", "AO" to "安哥拉", "MZ" to "莫桑比克",
        "FJ" to "斐济", "PG" to "巴布亚新几内亚"
    )

    private val LANG_CN = mapOf(
        "chi" to "中文", "zho" to "中文", "eng" to "英语", "spa" to "西班牙语",
        "ara" to "阿拉伯语", "hin" to "印地语", "ben" to "孟加拉语",
        "por" to "葡萄牙语", "rus" to "俄语", "jpn" to "日语", "kor" to "韩语",
        "fra" to "法语", "deu" to "德语", "ita" to "意大利语", "tur" to "土耳其语",
        "vie" to "越南语", "tha" to "泰语", "ind" to "印尼语", "msa" to "马来语",
        "fil" to "菲律宾语", "fas" to "波斯语", "urd" to "乌尔都语", "heb" to "希伯来语",
        "ukr" to "乌克兰语", "pol" to "波兰语", "nld" to "荷兰语", "gre" to "希腊语",
        "ell" to "希腊语", "cze" to "捷克语", "ces" to "捷克语", "rum" to "罗马尼亚语",
        "ron" to "罗马尼亚语", "hun" to "匈牙利语", "bul" to "保加利亚语",
        "srp" to "塞尔维亚语", "hrv" to "克罗地亚语", "slo" to "斯洛伐克语",
        "slk" to "斯洛伐克语", "sin" to "僧伽罗语", "tam" to "泰米尔语",
        "tel" to "泰卢固语", "kan" to "卡纳达语", "mar" to "马拉地语",
        "guj" to "古吉拉特语", "pan" to "旁遮普语", "nep" to "尼泊尔语",
        "mya" to "缅甸语", "khm" to "高棉语", "lao" to "老挝语",
        "amh" to "阿姆哈拉语", "swa" to "斯瓦希里语", "yor" to "约鲁巴语",
        "hau" to "豪萨语", "afr" to "南非荷兰语", "cat" to "加泰罗尼亚语",
        "dan" to "丹麦语", "fin" to "芬兰语", "nor" to "挪威语", "swe" to "瑞典语",
        "isl" to "冰岛语", "alb" to "阿尔巴尼亚语", "sqi" to "阿尔巴尼亚语",
        "mac" to "马其顿语", "mkd" to "马其顿语", "geo" to "格鲁吉亚语",
        "kat" to "格鲁吉亚语", "arm" to "亚美尼亚语", "hye" to "亚美尼亚语",
        "aze" to "阿塞拜疆语", "kaz" to "哈萨克语", "uzb" to "乌兹别克语",
        "mon" to "蒙古语", "khk" to "蒙古语", "bos" to "波斯尼亚语",
        "slv" to "斯洛文尼亚语", "est" to "爱沙尼亚语", "lav" to "拉脱维亚语",
        "lit" to "立陶宛语", "gle" to "爱尔兰语", "gla" to "苏格兰盖尔语",
        "wel" to "威尔士语", "cyc" to "威尔士语"
    )

    fun countryCn(code: String?): String {
        if (code.isNullOrBlank()) return ""
        return COUNTRY_CN[code.uppercase()] ?: code.uppercase()
    }

    fun langCn(code: String?): String {
        if (code.isNullOrBlank()) return ""
        return LANG_CN[code.lowercase()] ?: code.lowercase()
    }

    /** 将语言代码列表转为中文名，用 " · " 连接 */
    fun langsCn(langs: List<String>?): String {
        if (langs.isNullOrEmpty()) return ""
        return langs.map { langCn(it) }.filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
