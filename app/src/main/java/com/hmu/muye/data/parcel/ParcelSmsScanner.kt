package com.hmu.muye.data.parcel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * 可选短信取件码扫描器：
 * 仅在用户主动授权 READ_SMS 并点击「扫描近 7 天短信」时读取收件箱，全部在本地解析，不联网。
 */
object ParcelSmsScanner {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun scanRecentDays(
        context: Context,
        store: ParcelStore,
        days: Int = 7,
    ): Int {
        if (!hasPermission(context)) return 0
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        var added = 0
        val projection = arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.DATE} >= ?"
        val selectionArgs = arrayOf(cutoff.toString())
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)
                while (cursor.moveToNext()) {
                    val body = if (bodyCol >= 0) cursor.getString(bodyCol).orEmpty() else ""
                    if (store.ingestText(body, source = "历史短信") != null) {
                        added++
                    }
                }
            }
        }
        return added
    }
}
