package com.hmu.muye.data.ujing

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UjingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: UjingClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = UjingClient(server.url("").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends sms captcha and logs in with expected headers`() {
        server.enqueue(MockResponse().setBody("""{"code":0,"message":"ok"}"""))
        val cap = client.sendSmsCaptcha("13800138000")
        assertTrue(cap is UjingResult.Success)
        val req1 = server.takeRequest()
        assertEquals("GET", req1.method)
        assertEquals("/captcha?mobile=13800138000", req1.path)
        assertEquals("ZI", req1.getHeader("x-app-code"))

        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"token":"jwt-xyz"}}"""))
        val login = client.loginWithSms("13800138000", "1234")
        assertTrue(login is UjingResult.Success)
        assertEquals("jwt-xyz", (login as UjingResult.Success).data)
        val req2 = server.takeRequest()
        assertEquals("/login", req2.path)
        assertEquals("ZI", req2.getHeader("x-app-code"))
    }

    @Test
    fun `queries nearby stores with lont parameter and parses storeInfo`() {
        server.enqueue(
            MockResponse().setBody(
                """
                    {
                      "code": 0,
                      "data": [
                        {
                          "id": "st-1",
                          "name": "龙子湖5号楼",
                          "address": "龙子湖北路6号",
                          "storeInfo": [
                            {"category": 1, "access": 3, "num": 8},
                            {"category": 4, "access": 5, "num": 6}
                          ]
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )
        val res = client.nearbyStores("Bearer jwt-xyz", keyword = "龙子湖")
        assertTrue(res is UjingResult.Success)
        val stores = (res as UjingResult.Success).data
        assertEquals(1, stores.size)
        assertEquals("龙子湖5号楼", stores[0].name)
        assertEquals("洗衣机", stores[0].categories[0].label)
        assertEquals(3, stores[0].categories[0].available)
        assertEquals("吹风机", stores[0].categories[1].label)

        val req = server.takeRequest()
        assertEquals("Bearer jwt-xyz", req.getHeader("Authorization"))
        assertEquals("ZA", req.getHeader("x-app-code"))
        assertNotNull(req.requestUrl!!.queryParameter("lont"))
        assertNull(req.requestUrl!!.queryParameter("lng"))
        assertEquals("龙子湖", req.requestUrl!!.queryParameter("keyword"))
    }

    @Test
    fun `handles 1614 reserve disabled and parses scanWasherCode`() {
        server.enqueue(MockResponse().setBody("""{"code":1614,"message":"暂不支持预约，请扫码洗衣"}"""))
        val devs = client.storeDevices("jwt-xyz", "st-1")
        assertTrue(devs is UjingResult.Success)
        assertTrue((devs as UjingResult.Success).data.isEmpty())
        val reserveReq = server.takeRequest()
        assertEquals("/devices/reserve?storeId=st-1", reserveReq.path)
        assertEquals("ZA", reserveReq.getHeader("x-app-code"))

        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"deviceId":"d99","deviceName":"1号吹风机","category":4,"isIdle":true,"remainTime":0}}""",
            ),
        )
        val scan = client.scanDeviceQr("jwt-xyz", "https://phoenix.ujing.online/q/99")
        assertTrue(scan is UjingResult.Success)
        val info = (scan as UjingResult.Success).data
        assertEquals("1号吹风机", info.name)
        assertEquals("吹风机", info.typeName)
        assertTrue(info.available)
        val req = server.takeRequest()
        assertEquals("BI", req.getHeader("x-app-code"))
        assertEquals("1.1.30", req.getHeader("weex-version"))
    }

    @Test
    fun `detects 401 and jwt expiry`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"message":"JWT token is missing"}"""))
        val res = client.scanDeviceQr("bad", "qr")
        assertTrue(res is UjingResult.SessionExpired)
    }

    @Test
    fun `maps Aliyun WAF 405 html on captcha to actionable wechat guidance`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(405)
                .setBody("""<!doctypehtml><html><div id="block_message">blocked</div>errors.aliyun.com</html>"""),
        )
        val res = client.sendSmsCaptcha("13800138000")
        assertTrue(res is UjingResult.Error)
        val msg = (res as UjingResult.Error).message
        assertTrue(msg.contains("微信小程序") || msg.contains("获取短信验证码"))
    }
}
