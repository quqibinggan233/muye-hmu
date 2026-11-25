package com.hmu.muye.data.parcel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 一键直达各平台出库身份码、微信/支付宝扫码及校内常用 App/网址。
 */
object IdentityCodeShortcuts {

    private const val TAOBAO_AUTH_CODE_URI =
        "taobao://m.taobao.com/tbopen/index.html?h5Url=https://market.m.taobao.com/app/cn-yz/multi-activity/authCode.html"
    private const val PDD_PKG = "com.xunmeng.pinduoduo"
    private const val TAOBAO_PKG = "com.taobao.taobao"
    private const val CAINIAO_PKG = "com.cainiao.wireless"
    private const val WECHAT_PKG = "com.tencent.mm"
    private const val ALIPAY_PKG = "com.eg.android.AlipayGphone"
    private const val ALIPAY_SCAN_URI = "alipays://platformapi/startapp?saId=10000007"
    private const val YIXIAOYUAN_PKG = "cn.com.yunma.school.app"
    private const val LEZAI_PKG = "com.starthing.cliveapp"

    fun openTaobaoIdentityCode(context: Context) {
        if (tryStartUri(context, TAOBAO_AUTH_CODE_URI)) return
        if (tryLaunchPackage(context, CAINIAO_PKG)) return
        if (tryLaunchPackage(context, TAOBAO_PKG)) return
        toast(context, "未检测到淘宝或菜鸟 App")
    }

    fun openPinduoduo(context: Context) {
        if (tryLaunchPackage(context, PDD_PKG)) return
        toast(context, "未检测到拼多多 App")
    }

    fun openWeChat(context: Context) {
        if (tryLaunchPackage(context, WECHAT_PKG)) return
        toast(context, "未检测到微信 App")
    }

    fun openYixiaoyuan(context: Context) {
        if (tryLaunchPackage(context, YIXIAOYUAN_PKG)) return
        toast(context, "未检测到易校园 App（可在官方应用商店下载或进入微信端）")
    }

    fun openLezai(context: Context) {
        if (tryLaunchPackage(context, LEZAI_PKG)) return
        if (tryLaunchPackage(context, WECHAT_PKG)) {
            toast(context, "未安装乐仔生活 App，已为你打开微信")
            return
        }
        toast(context, "未检测到乐仔生活或微信 App")
    }

    fun openAlipayScan(context: Context) {
        if (tryStartUri(context, ALIPAY_SCAN_URI)) return
        if (tryLaunchPackage(context, ALIPAY_PKG)) return
        toast(context, "未检测到支付宝 App")
    }

    fun openScannedPaymentOrWeb(context: Context, rawContent: String) {
        val text = rawContent.trim()
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true) ||
            text.contains("://")
        ) {
            if (!tryStartUri(context, text)) {
                toast(context, "无法直接拉起该链接：$text")
            }
        } else {
            toast(context, "已识别二维码内容：$text")
        }
    }

    fun openUrl(context: Context, url: String) {
        if (!tryStartUri(context, url)) {
            toast(context, "无法打开链接")
        }
    }

    fun tryLaunchPackage(context: Context, pkg: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launch); true }.getOrDefault(false)
    }

    private fun tryStartUri(context: Context, uriString: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
