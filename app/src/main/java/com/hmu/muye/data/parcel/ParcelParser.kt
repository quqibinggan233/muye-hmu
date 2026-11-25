package com.hmu.muye.data.parcel

/**
 * 纯本地快递取件码解析器：
 * 覆盖菜鸟驿站、妈妈驿站、兔喜生活、丰巢、顺丰、京东、极兔及自定义正则规则；
 * 显式过滤验证码/登录动态码，防止误把短信验证码识别为取件码。
 */
object ParcelParser {

    data class ParsedParcel(
        val code: String,
        val station: String,
        val carrier: String,
    )

    private val PARCEL_KEYWORDS = listOf(
        "取件码", "提货码", "取货码", "出库码", "驿站", "快递柜", "丰巢", "菜鸟",
        "妈妈驿站", "兔喜", "熊猫快收", "速递易", "包裹", "快件", "自提点", "代收", "已到", "滞留",
    )

    private val OTP_BLOCK_KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "动态密码", "登录密码", "确认码", "安全码", "支付密码",
        "切勿泄露", "请勿转发", "切勿告知", "打死不要说",
    )

    private val CODE_PATTERNS = listOf(
        // 显式标注：取件码 / 提货码 / 取货码 / 凭码 后面紧跟的字母数字/连字符组合
        Regex("""(?:取件码|提货码|取货码|取件号|凭取件码|凭码)\s*[是为：:【\[]?\s*([A-Za-z0-9][A-Za-z0-9\-*#]{2,18})\s*[】\]]?"""),
        // 常见货架格式：3-2-4082、A12-304、18-092、02-05-103、A1-2-304
        Regex("""(?<![\d.])([A-Za-z]?\d{1,3}-\d{1,4}(?:-\d{1,5})?)(?![\d.])"""),
        // 6-10 位纯数字快递柜取件码（需上下文已确认是快递通知）
        Regex("""(?<!\d)(\d{6,10})(?!\d)"""),
    )

    private val CARRIER_KEYWORDS = listOf(
        "顺丰" to "顺丰速运",
        "SF" to "顺丰速运",
        "京东" to "京东快递",
        "极兔" to "极兔速递",
        "圆通" to "圆通速递",
        "中通" to "中通快递",
        "申通" to "申通快递",
        "韵达" to "韵达快递",
        "邮政" to "中国邮政",
        "EMS" to "EMS",
        "德邦" to "德邦快递",
        "丹鸟" to "丹鸟物流",
    )

    private val STATION_PATTERNS = listOf(
        Regex("""【([^】]{2,20})】"""),
        Regex("""\[([^\]]{2,20})\]"""),
    )

    fun parse(
        rawText: String,
        customRules: List<CustomParcelRule> = emptyList(),
    ): ParsedParcel? {
        val text = rawText.trim().replace('\n', ' ')
        if (text.length < 6) return null

        // 优先匹配用户自定义的驿站正则（组 1 或整段）
        for (rule in customRules) {
            val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: continue
            val match = regex.find(text) ?: continue
            val code = (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value).trim()
            if (code.length in 3..20) {
                return ParsedParcel(
                    code = code,
                    station = rule.stationLabel.ifBlank { detectStation(text) },
                    carrier = detectCarrier(text),
                )
            }
        }

        val hasParcelSignal = PARCEL_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        if (!hasParcelSignal) return null

        val looksLikeOtp = OTP_BLOCK_KEYWORDS.any { text.contains(it, ignoreCase = true) } &&
            !text.contains("驿站") && !text.contains("取件码") && !text.contains("快递柜") && !text.contains("丰巢")
        if (looksLikeOtp) return null

        var code: String? = null
        for (pattern in CODE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val candidate = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (isPlausibleCode(candidate, text)) {
                code = candidate
                break
            }
        }
        val finalCode = code ?: return null

        return ParsedParcel(
            code = finalCode,
            station = detectStation(text),
            carrier = detectCarrier(text),
        )
    }

    private fun isPlausibleCode(candidate: String, fullText: String): Boolean {
        if (candidate.length < 3 || candidate.length > 20) return false
        if (candidate.all { it == '0' }) return false
        // 排除 11 位手机号（避免把驿站联系人电话当取件码）
        if (candidate.length == 11 && candidate.startsWith("1") && candidate.all { it.isDigit() }) return false
        // 排除日期片段（如 2026-11-24）
        if (Regex("""^20\d{2}-\d{1,2}-\d{1,2}$""").matches(candidate)) return false
        // 排除明显是尾号/客服电话的短串（如 "尾号1234" 紧挨着）
        val idx = fullText.indexOf(candidate)
        if (idx > 0) {
            val prefix = fullText.substring(maxOf(0, idx - 4), idx)
            if (prefix.contains("尾号") || prefix.contains("联系") || prefix.contains("电话")) {
                return false
            }
        }
        return true
    }

    private fun detectCarrier(text: String): String {
        for ((kw, name) in CARRIER_KEYWORDS) {
            if (text.contains(kw, ignoreCase = true)) return name
        }
        return ""
    }

    private fun detectStation(text: String): String {
        val known = listOf(
            "菜鸟驿站" to "菜鸟驿站",
            "菜鸟" to "菜鸟驿站",
            "妈妈驿站" to "妈妈驿站",
            "兔喜生活" to "兔喜生活",
            "兔喜" to "兔喜生活",
            "丰巢" to "丰巢快递柜",
            "熊猫快收" to "熊猫快收",
            "速递易" to "速递易",
            "近邻宝" to "近邻宝",
        )
        for ((kw, label) in known) {
            if (text.contains(kw, ignoreCase = true)) return label
        }
        for (p in STATION_PATTERNS) {
            val tag = p.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (tag.isNotBlank() && !tag.contains("通知")) return tag
        }
        return "校园驿站"
    }
}
