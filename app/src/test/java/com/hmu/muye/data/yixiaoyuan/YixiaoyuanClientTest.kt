package com.hmu.muye.data.yixiaoyuan

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

class YixiaoyuanClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: YixiaoyuanClient

    private val ymId = "1234567890123456789"
    private val deviceId = "ym-0123456789abcdef0123456789abcdef"
    private val uuToken = "0123456789abcdef0123456789abcdef"
    private val fixedNow = 1700000000000L

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = YixiaoyuanClient(
            baseUrl = server.url("").toString().trimEnd('/'),
            now = { fixedNow },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login returns credential with uuToken`() {
        server.enqueue(
            MockResponse().setBody(
                """{"statusCode":0,"message":"操作成功","success":true,
                   "data":{"token":"tok-abc123","uuToken":"$uuToken","id":"$ymId","schoolCode":"10469"}}""",
            ),
        )
        val result = client.loginByVerificationCode("13800138000", "123456", deviceId)
        assertTrue(result is YixiaoyuanResult.Success)
        val cred = (result as YixiaoyuanResult.Success).data
        assertEquals("tok-abc123", cred.token)
        assertEquals(uuToken, cred.uuToken)
        assertEquals(ymId, cred.ymId)
        assertTrue(cred.signedIn)

        val req = server.takeRequest()
        assertEquals("/login/doLoginByVerificationCode", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("10469", body.optString("schoolCode"))
        assertEquals("13800138000", body.optString("mobilePhone"))
        assertEquals("123456", body.optString("verificationCode"))
        // 登录提交阶段服务端不校验签名，客户端也不应伪造 token
        assertEquals("", body.optString("token"))
    }

    @Test
    fun `login without uuToken is rejected locally`() {
        server.enqueue(
            MockResponse().setBody("""{"statusCode":0,"data":{"token":"tok","id":"$ymId"}}"""),
        )
        val result = client.loginByVerificationCode("13800138000", "123456", deviceId)
        assertTrue(result is YixiaoyuanResult.Error)
    }

    @Test
    fun `card money posts signed body with nt and token`() {
        server.enqueue(
            MockResponse().setBody("""{"statusCode":0,"message":"操作成功","data":"12.30","success":true}"""),
        )
        val cred = YixiaoyuanCredential(
            token = "tok-abc123", uuToken = uuToken, ymId = ymId,
            deviceId = deviceId, schoolCode = "10469",
        )
        val result = client.getCardMoney(cred)
        assertTrue(result is YixiaoyuanResult.Success)
        assertEquals(12.30, (result as YixiaoyuanResult.Success).data, 0.0001)

        val req = server.takeRequest()
        assertEquals("/compus/user/getCardMoney", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals(fixedNow, body.optLong("nt"))
        assertEquals("tok-abc123", body.optString("token"))
        assertEquals(ymId, body.optString("ymId"))
        assertEquals(deviceId, body.optString("deviceId"))

        // 签名必须与独立实现的黄金向量一致
        assertEquals("pHGG7NtQsud6YPmtzNiusp2A5/N6EsNsrLsDCUnLjIY=", req.getHeader("sign"))
    }

    @Test
    fun `status 204 maps to session expired`() {
        server.enqueue(MockResponse().setBody("""{"statusCode":204,"message":"请重新登录"}"""))
        val cred = YixiaoyuanCredential(token = "t", uuToken = uuToken, ymId = ymId, deviceId = deviceId)
        assertTrue(client.getCardMoney(cred) is YixiaoyuanResult.SessionExpired)
    }

    @Test
    fun `status 219 maps to session expired`() {
        server.enqueue(MockResponse().setBody("""{"statusCode":219,"message":"鉴权失败","bizCode":"00010"}"""))
        val cred = YixiaoyuanCredential(token = "t", uuToken = uuToken, ymId = ymId, deviceId = deviceId)
        assertTrue(client.getCardMoney(cred) is YixiaoyuanResult.SessionExpired)
    }

    @Test
    fun `unsigned credential short circuits`() {
        val result = client.getCardMoney(YixiaoyuanCredential(deviceId = deviceId))
        assertTrue(result is YixiaoyuanResult.Error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `generated device id matches official format`() {
        val id = YixiaoyuanClient.newDeviceId()
        assertTrue(id, id.startsWith("ym-") && id.length == 35)
        assertTrue(id.removePrefix("ym-").all { it in "0123456789abcdef" })
        assertNotNull(id)
    }

    @Test
    fun `login by password fetches public key encrypts with pkcs1 and returns credential`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        server.enqueue(
            MockResponse().setBody(
                """{"statusCode":0,"message":"操作成功","data":{"publicKey":"$pubB64"},"success":true}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"statusCode":0,"message":"操作成功","success":true,
                   "data":{"token":"tok-pwd","uuToken":"$uuToken","id":"$ymId","schoolCode":"10469"}}""",
            ),
        )

        val result = client.loginByPassword("13800138000", "MockPass!123", deviceId)
        assertTrue(result is YixiaoyuanResult.Success)
        val cred = (result as YixiaoyuanResult.Success).data
        assertEquals("tok-pwd", cred.token)
        assertEquals(uuToken, cred.uuToken)
        assertEquals(ymId, cred.ymId)
        assertEquals("13800138000", cred.mobile)

        val reqKey = server.takeRequest()
        assertEquals("/login/getPublicKey", reqKey.path)

        val reqLogin = server.takeRequest()
        assertEquals("/login/doLoginByPwd", reqLogin.path)
        val loginBody = JSONObject(reqLogin.body.readUtf8())
        assertEquals("13800138000", loginBody.optString("mobilePhone"))
        assertEquals("", loginBody.optString("token"))

        val cipherBytes = Base64.getDecoder().decode(loginBody.optString("password"))
        val decrypted = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, kp.private)
            String(doFinal(cipherBytes), Charsets.UTF_8)
        }
        assertEquals("MockPass!123", decrypted)
    }

    @Test
    fun `login by password propagates server error`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        server.enqueue(MockResponse().setBody("""{"statusCode":0,"data":{"publicKey":"$pubB64"}}"""))
        server.enqueue(MockResponse().setBody("""{"statusCode":-1,"message":"密码错误","success":false}"""))

        val result = client.loginByPassword("13800138000", "WrongPass", deviceId)
        assertTrue(result is YixiaoyuanResult.Error)
        assertEquals("密码错误", (result as YixiaoyuanResult.Error).message)
    }
}
