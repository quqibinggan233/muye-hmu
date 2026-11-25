package com.hmu.muye.data.campus

import com.fengkao.timetable.data.model.PeriodTime

/**
 * 河南牧业经济学院（HMU）龙子湖校区静态速查数据与默认作息配置。
 */
object HmuCampusDirectory {

    const val LONGZIHU_CAMPUS_ID = "5cb0e792114a0e30e063cb1ea8c0e3bb"

    data class HmuBuilding(
        val id: String,
        val code: String,
        val name: String,
    )

    data class DirectoryEntry(
        val title: String,
        val detail: String,
        val copyText: String = "",
    )

    val LONGZIHU_BUILDINGS: List<HmuBuilding> = listOf(
        HmuBuilding("5cb12fea30420e4ce063cb1ea8c0d87c", "401", "1号教学楼"),
        HmuBuilding("5cb12fea30430e4ce063cb1ea8c0d87c", "402", "2号教学楼A座"),
        HmuBuilding("5cb12fea30450e4ce063cb1ea8c0d87c", "404", "2号教学楼B座"),
        HmuBuilding("5cb12fea30440e4ce063cb1ea8c0d87c", "403", "2号教学楼C座"),
        HmuBuilding("5cb12fea30480e4ce063cb1ea8c0d87c", "407", "生工组团A区"),
        HmuBuilding("5cb12fea30460e4ce063cb1ea8c0d87c", "405", "生工组团B区"),
        HmuBuilding("5cb12fea30470e4ce063cb1ea8c0d87c", "406", "生工组团C区"),
        HmuBuilding("5cb12fea304a0e4ce063cb1ea8c0d87c", "409", "生工组团"),
        HmuBuilding("5cb12fea30490e4ce063cb1ea8c0d87c", "408", "大学生活动中心"),
        HmuBuilding("5cb12fea30500e4ce063cb1ea8c0d87c", "504", "体育场馆"),
        HmuBuilding("5cb12fea304f0e4ce063cb1ea8c0d87c", "01", "畜产品研究院"),
        HmuBuilding("5cb12fea30510e4ce063cb1ea8c0d87c", "02", "苗圃"),
    )

    val DEFAULT_PERIOD_TIMES: List<PeriodTime> = listOf(
        PeriodTime(1, 8, 20, 9, 5),
        PeriodTime(2, 9, 15, 10, 0),
        PeriodTime(3, 10, 20, 11, 5),
        PeriodTime(4, 11, 15, 12, 0),
        PeriodTime(5, 14, 0, 14, 45),
        PeriodTime(6, 14, 55, 15, 40),
        PeriodTime(7, 16, 0, 16, 45),
        PeriodTime(8, 16, 55, 17, 40),
        PeriodTime(9, 19, 0, 19, 45),
        PeriodTime(10, 19, 55, 20, 40),
        PeriodTime(11, 20, 50, 21, 35),
        PeriodTime(12, 21, 45, 22, 30),
    )

    val CALENDAR_NOTES: List<DirectoryEntry> = listOf(
        DirectoryEntry("教务系统入口", "超星综合教学管理：hnuahe.jw.chaoxing.com", "https://hnuahe.jw.chaoxing.com/admin/login"),
        DirectoryEntry("秋季学期参考", "一般 9 月初开课，共 16–18 教学周（可在「我的 → 学期设置」微调第一周周一）"),
        DirectoryEntry("春季学期参考", "一般 2 月末 / 3 月初开课，共 16–18 教学周"),
        DirectoryEntry("宿舍用电规则", "本校宿舍不收电费，无需电费充值"),
    )

    val LONGZIHU_PLACES: List<DirectoryEntry> = listOf(
        DirectoryEntry("龙子湖校区地址", "郑州市郑东新区龙子湖高校园区龙子湖北路 6 号"),
        DirectoryEntry("饮水设备", "慧生活 798（已升级 3.1.10 协议，支持启停、余额与账单查看）"),
        DirectoryEntry("浴室洗澡", "易校园获取 8 位动态用水码，在浴室水控机输入即可"),
        DirectoryEntry("吹风机", "宿舍楼为「U 净」（支持门店空闲查看与扫码查机）；浴室为「合肥海鸟」扫码计时"),
        DirectoryEntry("宿舍洗衣机", "乐仔生活（快捷直达）"),
        DirectoryEntry("图书馆预约", "通过河南牧业经济学院图书馆微信公众号进入选座"),
    )

    val BUILTIN_PORTALS: List<QuickShortcut> = listOf(
        QuickShortcut(
            id = "builtin_jw",
            title = "超星教务网页端",
            subtitle = "hnuahe.jw.chaoxing.com",
            targetUri = "https://hnuahe.jw.chaoxing.com/admin/login",
            builtIn = true,
        ),
        QuickShortcut(
            id = "builtin_jwc",
            title = "河牧教务处官网",
            subtitle = "jwc.hnuahe.edu.cn",
            targetUri = "https://jwc.hnuahe.edu.cn/",
            builtIn = true,
        ),
        QuickShortcut(
            id = "builtin_home",
            title = "学校官网首页",
            subtitle = "www.hnuahe.edu.cn",
            targetUri = "https://www.hnuahe.edu.cn/",
            builtIn = true,
        ),
    )
}
