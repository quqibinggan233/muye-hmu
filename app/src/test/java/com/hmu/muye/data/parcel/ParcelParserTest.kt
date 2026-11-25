package com.hmu.muye.data.parcel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParcelParserTest {

    @Test
    fun `parses cainiao sf parcel code`() {
        val parsed = ParcelParser.parse("【菜鸟驿站】您的顺丰快递已到龙子湖生活区，凭取件码3-2-4082到驿站取件")
        assertNotNull(parsed)
        assertEquals("3-2-4082", parsed!!.code)
        assertEquals("菜鸟驿站", parsed.station)
        assertEquals("顺丰速运", parsed.carrier)
    }

    @Test
    fun `parses mama station jd parcel code`() {
        val parsed = ParcelParser.parse("【妈妈驿站】您有京东快递到达东门驿站，取件码：18-092，请及时领取")
        assertNotNull(parsed)
        assertEquals("18-092", parsed!!.code)
        assertEquals("妈妈驿站", parsed.station)
        assertEquals("京东快递", parsed.carrier)
    }

    @Test
    fun `parses tuxi and fcbox locker codes`() {
        val tuxi = ParcelParser.parse("[兔喜生活] 极兔速递包裹已到，请凭提货码A12-304取件")
        assertNotNull(tuxi)
        assertEquals("A12-304", tuxi!!.code)
        assertEquals("兔喜生活", tuxi.station)
        assertEquals("极兔速递", tuxi.carrier)

        val fc = ParcelParser.parse("【丰巢】您的包裹已存放至3号柜，凭取件码84920134取件")
        assertNotNull(fc)
        assertEquals("84920134", fc!!.code)
        assertEquals("丰巢快递柜", fc.station)
    }

    @Test
    fun `supports custom user regex rules`() {
        val rule = CustomParcelRule("1", "龙子湖西门", """取货编号[:：]\s*([A-Z]\d+)""")
        val parsed = ParcelParser.parse("您好，中通包裹已入库，取货编号：K9042", listOf(rule))
        assertNotNull(parsed)
        assertEquals("K9042", parsed!!.code)
        assertEquals("龙子湖西门", parsed.station)
        assertEquals("中通快递", parsed.carrier)
    }

    @Test
    fun `rejects otp verification codes and phone numbers`() {
        assertNull(ParcelParser.parse("【支付宝】验证码：583920，用于账号登录，切勿泄露给他人。"))
        assertNull(ParcelParser.parse("【校园服务】您的短信动态密码为 901234，请勿转发。"))
        assertNull(ParcelParser.parse("【校园驿站】您有新包裹到达，如有疑问请联系13800138000"))
    }
}
