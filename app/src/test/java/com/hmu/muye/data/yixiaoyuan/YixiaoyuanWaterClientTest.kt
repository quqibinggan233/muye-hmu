package com.hmu.muye.data.yixiaoyuan

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YixiaoyuanWaterClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: YixiaoyuanWaterClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = YixiaoyuanWaterClient(baseUrl = server.url("").toString().trimEnd('/'))
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
}
