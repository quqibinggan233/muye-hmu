package com.hmu.muye.data.jw

import com.fengkao.timetable.data.TermSpec
import com.fengkao.timetable.data.model.StudentInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDate

class ChaoxingJwClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ChaoxingJwClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = ChaoxingJwClient(server.url("").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `diffs newly published and updated grades`() {
        val old = listOf(
            GradeItem("1", "25-26 第1学期", "高等数学", "必修", 4.0, "86", 3.6, true),
            GradeItem("2", "25-26 第1学期", "大学英语", "必修", 3.0, "80", 3.0, true),
        )
        val incoming = listOf(
            GradeItem("1", "25-26 第1学期", "高等数学", "必修", 4.0, "86", 3.6, true),
            GradeItem("2", "25-26 第1学期", "大学英语", "必修", 3.0, "84", 3.4, true),
            GradeItem("3", "25-26 第1学期", "计算机基础", "必修", 2.0, "92", 4.2, true),
        )
        val tagged = ChaoxingJwClient.diffNewGrades(old, incoming)
        assertFalse(tagged[0].isNew)
        assertTrue(tagged[1].isNew)
        assertTrue(tagged[2].isNew)
    }

    @Test
    fun `computes weighted average and gpa summary`() {
        val grades = listOf(
            GradeItem("1", "25-26 第1学期", "高数", "必修", 4.0, "90", 4.0, true),
            GradeItem("2", "25-26 第1学期", "体育", "必修", 1.0, "80", 3.0, true),
        )
        val summary = ChaoxingJwClient.computeGpaSummary(grades)
        assertEquals(88.0, summary.weightedAverage, 0.01)
        assertEquals(3.8, summary.averageGpa, 0.01)
        assertEquals(5.0, summary.totalCredits, 0.01)
        assertEquals(2, summary.passedCount)
        assertEquals(2, summary.totalCount)
    }

    @Test
    fun `parses generic chaoxing schedule json and detects login page`() {
        val raw = """
            {
              "studentName": "张三",
              "className": "计科2401",
              "courses": [
                {
                  "courseName": "数据结构",
                  "dayOfWeek": 2,
                  "startPeriod": 3,
                  "endPeriod": 4,
                  "place": "龙子湖一教 302",
                  "teacher": "李老师",
                  "weeks": [1, 2, 3, 4],
                  "weeksText": "1-4周"
                }
              ]
            }
        """.trimIndent()
        val parsed = ChaoxingJwClient.parseScheduleJson(raw, "20240001", TermSpec(2026, 1))
        assertNotNull(parsed)
        assertEquals("张三", parsed!!.student.name)
        assertEquals(1, parsed.courses.size)
        assertEquals("数据结构", parsed.courses.first().name)
        assertEquals(3, parsed.courses.first().startPeriod)

        assertTrue(ChaoxingJwClient.looksLikeLoginPage("<form id=\"loginform\" action=\"/admin/login\">"))
        assertFalse(ChaoxingJwClient.looksLikeLoginPage("<html><title>教学综合服务平台</title></html>"))
    }

    @Test
    fun `parses sdpkkbList merges adjacent sections and handles odd even weeks`() {
        val raw = """
            {
              "ret": 0,
              "msg": "操作成功",
              "data": [
                {
                  "kcmc": "<b>数据结构</b>",
                  "tmc": "王老师",
                  "jxlmc": "1号教学楼",
                  "croommc": "1号教学楼302",
                  "xqmc": "龙子湖校区",
                  "xingqi": 1,
                  "djc": 1,
                  "djs": 1,
                  "zc": "1-8(单),10-12",
                  "zctype": ""
                },
                {
                  "kcmc": "<b>数据结构</b>",
                  "tmc": "王老师",
                  "jxlmc": "1号教学楼",
                  "croommc": "1号教学楼302",
                  "xqmc": "龙子湖校区",
                  "xingqi": 1,
                  "djc": 2,
                  "djs": 2,
                  "zc": "1-8(单),10-12",
                  "zctype": ""
                },
                {
                  "kcmc": "大学物理",
                  "tmc": "刘老师",
                  "jxlmc": "2号教学楼A座",
                  "croommc": "205",
                  "xqmc": "龙子湖校区",
                  "xingqi": 3,
                  "djc": 5,
                  "djs": 2,
                  "zc": "2-6",
                  "zctype": "2"
                }
              ]
            }
        """.trimIndent()
        val schedule = ChaoxingJwClient.parseChaoxingSdpkkbJson(raw, "20240001", TermSpec(2026, 1))
        assertNotNull(schedule)
        assertEquals(2, schedule!!.courses.size)

        val ds = schedule.courses[0]
        assertEquals("数据结构", ds.name)
        assertEquals("1号教学楼302", ds.place)
        assertEquals(1, ds.startPeriod)
        assertEquals(2, ds.endPeriod)
        assertEquals(listOf(1, 3, 5, 7, 10, 11, 12), ds.weeks)

        val phys = schedule.courses[1]
        assertEquals("2号教学楼A座 205", phys.place)
        assertEquals(5, phys.startPeriod)
        assertEquals(6, phys.endPeriod)
        assertEquals(listOf(2, 4, 6), phys.weeks)
    }

    @Test
    fun `fetchSchedule extracts hidden inputs regardless of attribute order`() {
        server.enqueue(
            MockResponse().setBody(
                """
                    <html>
                      <input type="hidden" value="UID-9988" id="xhid" />
                      <input id="xqdm" type="hidden" value="5cb0e792114a0e30e063cb1ea8c0e3bb" />
                      <input type="hidden" id="xnxq" value="2026-2027-1" />
                    </html>
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"ret":0,"msg":"","data":[{"kcmc":"算法设计","tmc":"张教授","jxlmc":"1号教学楼","croommc":"101","xqmc":"龙子湖校区","xingqi":2,"djc":3,"djs":4,"zc":"1-16"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"xm":"李同学","xh":"20240001","sznj":"2024","yxmc":"信息工程学院","zymc":"计算机科学与技术","bjmc":"计科2401"}""",
            ),
        )
        val result = client.fetchSchedule("JSESSIONID=abc", "20240001", TermSpec(2026, 1))
        assertTrue(result is JwFetchResult.Success)
        val schedule = (result as JwFetchResult.Success).data
        assertEquals(1, schedule.courses.size)
        assertEquals("算法设计", schedule.courses.first().name)
        assertEquals("李同学", schedule.student.name)
        assertEquals("计科2401", schedule.student.className)

        val req1 = server.takeRequest()
        assertEquals("/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=2026-2027-1", req1.path)
        val req2 = server.takeRequest()
        assertTrue(req2.path!!.startsWith("/admin/xsd/pkgl/xskb/sdpkkbList?"))
        assertEquals("UID-9988", req2.requestUrl!!.queryParameter("xhid"))
        val req3 = server.takeRequest()
        assertEquals("/admin/xsd/xsjbxx/getXjxxByXnxq", req3.path)
    }

    @Test
    fun `fetchGrades converts credit gpa translates kcxz codes and falls back to mobile json`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "ret": 0,
                  "results": [
                    {"id":"g1","xnxq":"2025-2026-2","kcmc":"数据结构","kcxz":"01","hdxf":4.0,"xfjd":"15.6","zhcj":"89","sfjg":"1"},
                    {"id":"g2","xnxq":"2025-2026-2","kcmc":"大学英语","kcxz":"06","hdxf":2.0,"xfjd":"7","zhcj":"良好"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val res1 = client.fetchGrades("JSESSIONID=ok")
        assertTrue(res1 is JwFetchResult.Success)
        val list1 = (res1 as JwFetchResult.Success).data
        assertEquals(2, list1.size)
        val english = list1.first { it.courseName == "大学英语" }
        val ds = list1.first { it.courseName == "数据结构" }
        assertEquals(3.5, english.gpa, 0.01)
        assertEquals("专业基础必修课", english.courseType)
        assertEquals(3.9, ds.gpa, 0.01)
        assertEquals("公共必修课", ds.courseType)
        val summary = ChaoxingJwClient.computeGpaSummary(list1)
        assertEquals((15.6 + 7.0) / 6.0, summary.averageGpa, 0.01)

        // 主接口返回空列表时回退到移动端 getXscjJson
        server.enqueue(MockResponse().setBody("""{"ret":0,"results":[]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"ret":0,"data":{"xscjList":[{"id":"g3","xnxq":"2025-2026-1","kcmc":"线性代数","kcxz":"公共必修","sjxf":3.0,"bfzzhcj":"90","sfjg":"1"}]}}""",
            ),
        )
        val res2 = client.fetchGrades("JSESSIONID=ok")
        assertTrue(res2 is JwFetchResult.Success)
        val list2 = (res2 as JwFetchResult.Success).data
        assertEquals(1, list2.size)
        assertEquals("线性代数", list2[0].courseName)
        assertEquals(4.0, list2[0].gpa, 0.01)
    }

    @Test
    fun `fetchExams parses chaoxing exam schedule json`() {
        server.enqueue(MockResponse().setBody("""{"ret":0,"data":{"xnxqh":"2026-2027-1"}}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"ret":0,"results":[{"id":"e1","kspcmc":"期末正考","ksrq":"2027-01-12","kscc":"09:00-11:00","sxkc":"数据库原理","kskc":"1号教学楼 305","kczwh":"24"}]}""",
            ),
        )
        val res = client.fetchExams("JSESSIONID=ok")
        assertTrue(res is JwFetchResult.Success)
        val exams = (res as JwFetchResult.Success).data
        assertEquals(1, exams.size)
        assertEquals("数据库原理", exams[0].courseName)
        assertEquals("2027-01-12 09:00-11:00", exams[0].timeText)
        assertEquals("1号教学楼 305", exams[0].place)
        assertEquals("24", exams[0].seatNo)
    }

    @Test
    fun `fetchEmptyRooms filters busy classrooms and returns free ones`() {
        server.enqueue(MockResponse().setBody("""{"ret":0,"data":{"xnxqh":"2026-2027-1"}}"""))
        server.enqueue(MockResponse().setBody("""{"ret":0,"data":["12"]}"""))
        server.enqueue(
            MockResponse().setBody(
                """
                    [
                      {"id":"r1","rbh":"40101","rmc":"1号楼101","jxlmc":"1号教学楼","encodeId":"enc1"},
                      {"id":"r2","rbh":"40102","rmc":"1号楼102","jxlmc":"1号教学楼","encodeId":"enc2"}
                    ]
                """.trimIndent(),
            ),
        )
        // Dispatch room responses regardless of executor thread scheduling
        server.enqueue(
            MockResponse().setBody("""[{"xingqi":2,"djc":3,"djs":4,"kcmc":"占课"}]"""),
        )
        server.enqueue(
            MockResponse().setBody("""[]"""),
        )
        val res = client.fetchEmptyRooms("JSESSIONID=ok", "1号教学楼", "3-4节", dayOfWeek = 2)
        assertTrue(res is JwFetchResult.Success)
        val free = (res as JwFetchResult.Success).data
        assertEquals(1, free.size)

        assertEquals("/admin/api/jcsj/xnxq/getCurrentXnxq", server.takeRequest().path)
        assertEquals("/admin/getCurrentPkZc", server.takeRequest().path)
        val roomsReq = server.takeRequest()
        assertEquals("/admin/jwxtgld/kbcx/getRommList", roomsReq.path)
        val roomsForm = URLDecoder.decode(roomsReq.body.readUtf8(), "UTF-8")
        assertTrue(roomsForm.contains("id=5cb12fea30420e4ce063cb1ea8c0d87c"))

        val date = ChaoxingJwClient.parseTermStartDateJson("""{"ksrq":"2026-09-07","xnxqh":"2026-2027-1"}""")
        assertEquals(LocalDate.of(2026, 9, 7), date)
    }

    @Test
    fun `fetchStudentInfo parses getXjxxByXnxq json and mergedWith prefers real studentId`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "xm": "测试同学",
                  "xh": "202501010101",
                  "sznj": "2025",
                  "yxmc": "食品工程学院",
                  "zymc": "食品科学与工程",
                  "bjmc": "食品2501"
                }
                """.trimIndent(),
            ),
        )
        val res = client.fetchStudentInfo("uid=ok")
        assertTrue(res is JwFetchResult.Success)
        val info = (res as JwFetchResult.Success).data
        assertEquals("测试同学", info.name)
        assertEquals("202501010101", info.studentId)
        assertEquals("2025", info.grade)
        assertEquals("食品工程学院", info.department)
        assertEquals("食品科学与工程", info.major)
        assertEquals("食品2501", info.className)
        assertTrue(info.isProfileComplete)

        val fromXuexitong = StudentInfo(name = "测试同学", studentId = "13800138000")
        val merged = fromXuexitong.mergedWith(info)
        assertEquals("202501010101", merged.studentId)
        assertEquals("食品2501", merged.className)
        assertTrue(merged.isProfileComplete)
    }

    @Test
    fun `loginWithPassword follows redirect and collects cookies`() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "uid=u1; Path=/"))
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/admin")
                .addHeader("Set-Cookie", "jw_uf=ok; Path=/")
                .addHeader("Set-Cookie", "puid=p1; Path=/"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "yysz=1; Path=/")
                .setBody("<html><title>超星综合教学管理系统</title></html>"),
        )

        val res = client.loginWithPassword("20240001", "MockJwPass!99")
        assertTrue(res is JwLoginResult.Success)
        val cookies = (res as JwLoginResult.Success).cookies
        assertTrue(cookies.contains("uid=u1"))
        assertTrue(cookies.contains("jw_uf=ok"))
        assertTrue(cookies.contains("puid=p1"))
        assertTrue(cookies.contains("yysz=1"))

        val initReq = server.takeRequest()
        assertEquals("GET", initReq.method)
        assertEquals("/admin/login", initReq.path)

        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals("/admin/login", postReq.path)
        val form = postReq.body.readUtf8()
        assertTrue(form.contains("username=20240001"))
        assertTrue(form.contains("rememberMe=1"))
        assertTrue(postReq.getHeader("Cookie")?.contains("uid=u1") == true)

        val followReq = server.takeRequest()
        assertEquals("GET", followReq.method)
        assertEquals("/admin", followReq.path)
    }

    @Test
    fun `loginWithPassword parses inline error variable`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>var error = "用户或密码错误, 请重试。";</script>"""),
        )

        val res = client.loginWithPassword("20240001", "bad")
        assertTrue(res is JwLoginResult.InvalidCredentials)
        assertEquals("用户或密码错误, 请重试。", (res as JwLoginResult.InvalidCredentials).message)
    }

    @Test
    fun `loginWithPassword returns NeedWebFallback on captcha redirect`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/admin/login?jcaptchaError=1"),
        )

        val res = client.loginWithPassword("20240001", "MockPass")
        assertTrue(res is JwLoginResult.NeedWebFallback)
    }

    @Test
    fun `verifySession uses admin endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><title>超星综合教学管理系统</title></html>"))
        assertTrue(client.verifySession("uid=u1"))
        val req = server.takeRequest()
        assertEquals("/admin", req.path)
    }

    @Test
    fun `relocates next-term retake passes back to initial failed term and deduplicates pass count`() {
        val rawWithFlag = """
            {
              "ret": 0,
              "results": [
                {"id":"f1","xnxq":"2025-2026-1","kcbh":"6123060","kcmc":"[6123060]无机及分析化学","kcxz":"06","hdxf":0.0,"zhcj":"45","sfjg":"0"},
                {"id":"r1","xnxq":"2025-2026-2","kcbh":"6123060","kcmc":"[6123060]无机及分析化学","kcxz":"06","sfbk":"1","hdxf":4.0,"xfjd":"4.0","zhcj":"60","sfjg":"1"},
                {"id":"f2","xnxq":"2025-2026-1","kcbh":"6122037","kcmc":"[6122037]中国近现代史纲要","kcxz":"01","hdxf":0.0,"zhcj":"52","sfjg":"0"},
                {"id":"r2","xnxq":"2025-2026-2","kcbh":"6122037","kcmc":"[6122037]中国近现代史纲要","kcxz":"01","hdxf":3.0,"xfjd":"3.0","zhcj":"60","sfjg":"1"},
                {"id":"cx","xnxq":"2025-2026-1","kcbh":"990001","kcmc":"普通化学","kcxz":"01","hdxf":0.0,"zhcj":"40","sfjg":"0"},
                {"id":"cx2","xnxq":"2025-2026-2","kcbh":"990001","kcmc":"普通化学","kcxz":"01","xdxz":"2","hdxf":2.0,"xfjd":"4.0","zhcj":"70","sfjg":"1"}
              ]
            }
        """.trimIndent()

        val parsed = ChaoxingJwClient.parseChaoxingGradesJson(rawWithFlag)
        assertEquals(4, parsed.size)

        val chem = parsed.single { it.courseName.contains("无机及分析化学") }
        assertEquals("2025-2026-1", chem.termLabel)
        assertEquals("补考", chem.examNature)
        assertEquals("2025-2026-2", chem.examTermLabel)
        assertEquals("45", chem.initialScoreText)
        assertTrue(chem.passed)

        val history = parsed.single { it.courseName.contains("中国近现代史纲要") }
        assertEquals("2025-2026-1", history.termLabel)
        assertEquals("补考", history.examNature)
        assertEquals("52", history.initialScoreText)

        // 重修应保留在原重修学期，不被回迁
        val rebuild = parsed.filter { it.courseName == "普通化学" }
        assertEquals(2, rebuild.size)
        val rebuildPass = rebuild.first { it.passed }
        assertEquals("2025-2026-2", rebuildPass.termLabel)
        assertEquals("重修", rebuildPass.examNature)

        val summaryTerm1 = ChaoxingJwClient.computeGpaSummary(parsed.filter { it.termLabel == "2025-2026-1" })
        assertEquals(3, summaryTerm1.totalCount)
        assertEquals(2, summaryTerm1.passedCount)
    }
}
