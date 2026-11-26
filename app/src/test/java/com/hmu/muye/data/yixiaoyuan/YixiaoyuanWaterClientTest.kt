package com.hmu.muye.data.yixiaoyuan

import com.hmu.muye.data.campus.YixiaoyuanState
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class YixiaoyuanWaterClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: YixiaoyuanWaterClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val mockBase = server.url("").toString().trimEnd('/')
        client = YixiaoyuanWaterClient(baseUrl = mockBase, authBaseUrl = mockBase)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `queryCode returns 8-digit ranCode with validity metadata`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "statusCode": 0,
                  "message": "操作成功",
                  "success": true,
                  "data": {
                    "ranCode": "12345678",
                    "ranValidTime": 360,
                    "ranValidTimes": 49,
                    "ranIntervalTime": 60,
                    "ranGetTimes": 29,
                    "numLength": "4",
                    "longTerm": "-1"
                  }
                }
                """.trimIndent(),
            ),
        )

        val res = client.queryCode("00000000-0000-0000-0000-000000000001")
        assertTrue(res is YixiaoyuanResult.Success)
        val waterCode = (res as YixiaoyuanResult.Success).data
        assertEquals("12345678", waterCode.code)
        assertEquals(360, waterCode.validSeconds)
        assertEquals(49, waterCode.remainTimes)
        assertEquals(60, waterCode.intervalSeconds)
        assertEquals(29, waterCode.usedTimes)
        assertFalse(waterCode.longTerm)

        val req = server.takeRequest()
        assertEquals("/app/hzsun/cloudwater/queryRanCode", req.path)
        assertEquals("shiroJID=00000000-0000-0000-0000-000000000001", req.getHeader("Cookie"))
        assertEquals("platform=YUNMA_APP", req.body.readUtf8())
    }

    @Test
    fun `longTerm 1 sets longTerm true and remainingSeconds MAX_VALUE`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "statusCode": 0,
                  "success": true,
                  "data": {
                    "ranCode": "87654321",
                    "ranValidTime": 0,
                    "ranValidTimes": 10,
                    "ranIntervalTime": 30,
                    "ranGetTimes": 1,
                    "longTerm": "1"
                  }
                }
                """.trimIndent(),
            ),
        )

        val res = client.queryCode("valid-jid")
        assertTrue(res is YixiaoyuanResult.Success)
        val waterCode = (res as YixiaoyuanResult.Success).data
        assertTrue(waterCode.longTerm)
        assertEquals(Int.MAX_VALUE, waterCode.remainingSeconds(waterCode.fetchedAt + 999_999_000L))
    }

    @Test
    fun `remainingSeconds counts down and clamps at zero`() {
        val code = WaterCode(
            code = "12345678",
            validSeconds = 360,
            remainTimes = 5,
            intervalSeconds = 60,
            usedTimes = 1,
            longTerm = false,
            fetchedAt = 1_000_000L,
        )
        assertEquals(300, code.remainingSeconds(1_060_000L))
        assertEquals(0, code.remainingSeconds(1_500_000L))
    }

    @Test
    fun `statusCode 204 maps to SessionExpired`() {
        server.enqueue(MockResponse().setBody("""{"statusCode":204,"message":"请重新登录"}"""))
        val res = client.queryCode("expired-jid")
        assertTrue(res is YixiaoyuanResult.SessionExpired)
    }

    @Test
    fun `empty ranCode maps to Error`() {
        server.enqueue(MockResponse().setBody("""{"statusCode":0,"success":true,"data":{"ranCode":""}}"""))
        val res = client.queryCode("valid-jid")
        assertTrue(res is YixiaoyuanResult.Error)
    }

    @Test
    fun `blank shiroJid short circuits without request`() {
        val res = client.queryCode("   ")
        assertTrue(res is YixiaoyuanResult.Error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `exchangeShiroJid exchanges ymId via getCodeV2 redirect and getUser4Authorize Set-Cookie`() {
        val base = server.url("").toString().trimEnd('/')
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "$base/water/zy/ranCode?ymCode=ymcode-xyz-123&platform=YUNMA_APP"),
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "shiroJID=11111111-2222-3333-4444-555555555555; Path=/; HttpOnly")
                .setBody("""{"statusCode":0,"success":true,"data":{"id":"1234567890123456789"}}"""),
        )

        val res = client.exchangeShiroJid("1234567890123456789", "10469")
        assertTrue(res is YixiaoyuanResult.Success)
        assertEquals("11111111-2222-3333-4444-555555555555", (res as YixiaoyuanResult.Success).data)

        val req1 = server.takeRequest()
        assertEquals("/authoriz/getCodeV2", req1.requestUrl?.encodedPath)
        assertTrue(req1.getHeader("User-Agent").orEmpty().contains("yunmaapp.NET"))
        assertEquals("1234567890123456789", req1.requestUrl?.queryParameter("unionid"))
        assertEquals(YixiaoyuanWaterClient.DEFAULT_YM_APP_ID, req1.requestUrl?.queryParameter("appid"))

        val req2 = server.takeRequest()
        assertEquals("/app/login/getUser4Authorize", req2.path)
        val body2 = req2.body.readUtf8()
        assertTrue(body2.contains("code=ymcode-xyz-123"))
        assertTrue(body2.contains("authType=2"))
        assertTrue(body2.contains("platform=YUNMA_APP"))
        assertFalse(body2.contains("schoolCode="))
    }

    @Test
    fun `exchangeShiroJid skips deleteMe Set-Cookie and picks valid shiroJID`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/water/zy/ranCode?ymCode=ymcode-shiro-test"),
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "shiroJID=deleteMe; Path=/; Max-Age=0")
                .addHeader("Set-Cookie", "shiroJID=33333333-4444-5555-6666-777777777777; Path=/; HttpOnly")
                .setBody("""{"statusCode":0,"success":true}"""),
        )

        val res = client.exchangeShiroJid("1234567890123456789")
        assertTrue(res is YixiaoyuanResult.Success)
        assertEquals("33333333-4444-5555-6666-777777777777", (res as YixiaoyuanResult.Success).data)
    }

    @Test
    fun `exchangeShiroJid handles 2-hop redirect and extracts html error message when failed`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/authoriz/step2?foo=bar"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/water/zy/ranCode?ymCode=ymcode-hop2"),
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "shiroJID=22222222-3333-4444-5555-666666666666; Path=/")
                .setBody("""{"statusCode":0,"success":true}"""),
        )

        val okRes = client.exchangeShiroJid("1234567890123456789")
        assertTrue(okRes is YixiaoyuanResult.Success)
        assertEquals("22222222-3333-4444-5555-666666666666", (okRes as YixiaoyuanResult.Success).data)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div>APP容器下缺少unionid</div></body></html>"""),
        )
        val errRes = client.exchangeShiroJid("1234567890123456789")
        assertTrue(errRes is YixiaoyuanResult.Error)
        assertTrue((errRes as YixiaoyuanResult.Error).message.contains("APP容器下缺少unionid"))
    }

    @Test
    fun `insertCode posts unPayName and platform with shiroJID cookie`() {
        server.enqueue(
            MockResponse().setBody(
                """{"statusCode":0,"success":true,"data":{"ranCode":"87654321","ranValidTime":7,"ranValidTimes":10,"ranGetTimes":3,"longTerm":"0"}}""",
            ),
        )

        val res = client.insertCode("cookie-jid-1", "我的订单")
        assertTrue(res is YixiaoyuanResult.Success)
        val code = (res as YixiaoyuanResult.Success).data
        assertEquals("87654321", code.code)
        assertEquals("有效 7 天", code.validityText())

        val req = server.takeRequest()
        assertEquals("/app/hzsun/cloudwater/insertRanCode", req.path)
        assertEquals("shiroJID=cookie-jid-1", req.getHeader("Cookie"))
        val decodedBody = URLDecoder.decode(req.body.readUtf8(), "UTF-8")
        assertTrue(decodedBody.contains("unPayName=我的订单"))
        assertTrue(decodedBody.contains("platform=YUNMA_APP"))
    }

    @Test
    fun `queryCode with autoGenerateIfEmpty calls insertRanCode when ranCode is blank and quota remains`() {
        server.enqueue(
            MockResponse().setBody("""{"statusCode":0,"success":true,"data":{"ranCode":"","ranGetTimes":5}}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"statusCode":0,"success":true,"data":{"ranCode":"24681357","ranValidTime":360,"ranValidTimes":10,"ranGetTimes":4,"longTerm":"-1"}}""",
            ),
        )

        val res = client.queryCode("cookie-jid-2", autoGenerateIfEmpty = true)
        assertTrue(res is YixiaoyuanResult.Success)
        val code = (res as YixiaoyuanResult.Success).data
        assertEquals("24681357", code.code)
        assertEquals("有效 360 分钟", code.validityText())
        assertEquals("有效 360 分钟 · 今日可取 4 次", code.summaryText())

        assertEquals("/app/hzsun/cloudwater/queryRanCode", server.takeRequest().path)
        assertEquals("/app/hzsun/cloudwater/insertRanCode", server.takeRequest().path)
    }

    @Test
    fun `YixiaoyuanState serialization persists showBalanceCard false with encodeDefaults`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(
            YixiaoyuanState.serializer(),
            YixiaoyuanState(hasCredential = true, showBalanceCard = false),
        )
        assertTrue(encoded.contains("\"showBalanceCard\":false"))
        val decoded = json.decodeFromString(YixiaoyuanState.serializer(), encoded)
        assertFalse(decoded.showBalanceCard)
        assertTrue(decoded.hasCredential)
    }

    @Test
    fun `cancelCode posts ranCode and platform with shiroJID cookie`() {
        server.enqueue(MockResponse().setBody("""{"statusCode":0,"message":"操作成功","success":true}"""))
        val okRes = client.cancelCode("cookie-jid-cancel", "87654321")
        assertTrue(okRes is YixiaoyuanResult.Success)
        val req = server.takeRequest()
        assertEquals("/app/hzsun/cloudwater/cancelRanCode", req.path)
        assertEquals("shiroJID=cookie-jid-cancel", req.getHeader("Cookie"))
        val body = URLDecoder.decode(req.body.readUtf8(), "UTF-8")
        assertTrue(body.contains("ranCode=87654321"))
        assertTrue(body.contains("platform=YUNMA_APP"))

        server.enqueue(MockResponse().setBody("""{"statusCode":204,"message":"请重新登录"}"""))
        val expiredRes = client.cancelCode("cookie-jid-cancel", "87654321")
        assertTrue(expiredRes is YixiaoyuanResult.SessionExpired)

        server.enqueue(MockResponse().setBody("""{"statusCode":500,"message":"该用水码已失效"}"""))
        val errRes = client.cancelCode("cookie-jid-cancel", "87654321")
        assertTrue(errRes is YixiaoyuanResult.Error)
        assertEquals("该用水码已失效", (errRes as YixiaoyuanResult.Error).message)
    }
}
