# 牧野生活（HMU）Phase 2 接口采集指南

本文档用于指导在个人真机或本地 Android 模拟器上安全地采集 Phase 2 所需的真实校园接口格式。

> ⚠️ **安全与隐私规范（务必遵守）**：
> 1. **只登录本人账号**，仅用于个人客户端协议适配与只读/自用控制，禁止压测、批量请求或绕过付费与验证码校验。
> 2. 提交或贴出任何抓包样本前，必须脱敏替换：`学号`、`手机号`、`姓名`、`Cookie / Token / shiroJID / Authorization`、`真实订单号`。
> 3. `water.properties`、`keystore.properties`、`*.jks` 严禁提交到 Git 仓库（`.gitignore` 已忽略）。

---

## 一、桌面浏览器 F12 即可采集的接口（最简单，无需模拟器）

### 1. 超星综合教学管理系统（`https://hnuahe.jw.chaoxing.com`）

1. 在电脑浏览器打开 `https://hnuahe.jw.chaoxing.com/admin/login`，按 `F12` 打开开发者工具 → 切到 **Network（网络）** → 勾选 **Preserve log** → 过滤 **Fetch/XHR**。
2. 正常登录你的账号（支持账密 / 统一认证 / 学习通扫码）。
3. 依次点击以下页面，记录对应 XHR 请求的 **URL、Method（GET/POST）、Query/Form 参数、以及脱敏后的 JSON 响应前 1 条样本**：
   - **个人课表**：重点看课程名、周次数组/文本、星期、起始节次、结束节次、教室、教师字段名。
   - **成绩查询**：重点看学期字段、课程名、学分、绩点、总评成绩、修读性质。
   - **考试安排**：重点看课程名、考试时间、考场地点、座位号字段。
   - **空教室查询**：重点看查询参数（校区/教学楼/日期/节次）与返回教室列表结构。
   - **校历 / 当前周次接口**（如有）：用于自动校准开学第一周日期。

### 2. 龙子湖校园网 Wi-Fi Portal 认证

1. 手机或笔记本连上龙子湖校园网 Wi-Fi 后，未认证状态下在浏览器打开任意 HTTP 测试页（如 `http://captive.apple.com` 或学校默认认证页），打开开发者工具 **Network**。
2. 在登录页输入账号密码点击登录，记录：
   - 认证页 URL 与主机 IP / 域名（例如是否携带 `wlanuserip`、`wlanacip`、`mac` 等 Query 参数）。
   - 点击登录发出的 `GET` 或 `POST` 接口 URL、参数名（如 `userId`、`password`、运营商后缀等）以及返回成功/失败的 JSON 或重定向标识。

### 3. 图书馆公众号预约链接确认

1. 在微信中打开河南牧业经济学院图书馆公众号的座位预约功能，点右上角 `...` → **复制链接**。
2. 观察链接是：
   - 独立域名 + 统一认证（可直接用 App 内置 WebView 打开，后续甚至可接原生预约）；还是
   - 强绑定 `mp.weixin.qq.com` / `open.weixin.qq.com` 的微信 `openid` 授权（则保留在 App 内一键跳转微信打开，避免破坏授权）。

---

## 二、Android 模拟器 / 真机采集准备（易校园、U 净、慧生活 798、乐仔生活）

你本机已配置好位于 `~/Library/Android/sdk` 的命令行 Android SDK（Apple Silicon `arm64` Mac）。

### 方案 A：使用本机 `arm64-v8a` Android 模拟器（方便你在电脑上登录账号操作）

> 下面命令会下载 Android 系统镜像与模拟器组件（约 1.5–2.5 GB 磁盘空间），需要时再执行：

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# 1. 安装 emulator 与 Android 15 (API 35) arm64-v8a 镜像
sdkmanager "emulator" "system-images;android-35;google_apis;arm64-v8a"

# 2. 创建名为 hmu_capture 的模拟器
avdmanager create avd -n hmu_capture -k "system-images;android-35;google_apis;arm64-v8a" -d "pixel_7"

# 3. 启动模拟器
emulator -avd hmu_capture
```

安装官方 App 到模拟器（需先从官网或可信渠道下载 APK）：

```bash
adb install /path/to/app.apk
```

### 方案 B：使用实体安卓手机或抓包工具

- **非 Root 环境抓包**：可使用 Reqable / PCAPdroid（配合桌面端 Charles / mitmproxy 代理）或在测试镜像上开启用户证书代理，只抓取待适配 App 自身的域名请求。

---

## 三、各生活服务需要确认的字段清单

### 1. 慧生活 798（喝水）

在根目录复制配置模板：

```bash
cp water.properties.example water.properties
```

需要填入两项（不提交 Git）：
- `API_GATEWAY=`（官方客户端 HTTPS 网关地址）
- `API_CID=`（客户端标识）

若需进一步参考 [`Kou-JunHao/Oasis-App`](https://github.com/Kou-JunHao/Oasis-App) 补齐余额查询与订单记录，可顺带采集：
- 钱包余额查询路由与返回字段（对比 `/ui/app/master` 中 `account` 字段）
- 消费/接水记录列表路由与分页参数

### 2. 易校园（洗澡 6 位用水码 + 一卡通余额）

重点抓取以下 3 条只读/出码接口（参考开源社区常见字段：`Cookie: shiroJID=...`、`ymId`）：
1. 登录态刷新 / 会话校验接口（确认 Cookie / Token 有效期）。
2. **一卡通余额查询接口**：确认返回字段中的余额（元/分）与卡状态。
3. **浴室 6 位用水码获取 / 刷新接口**：确认请求参数、返回码文本及有效倒计时字段。

### 3. 宿舍 U 净吹风机

参考 `Inonvation/JUWPHub` 已有研究（常见网关 `https://phoenix.ujing.online/api/v1`）：
1. 登录与 JWT Token 刷新接口，以及必要请求头。
2. 龙子湖宿舍楼层设备列表查询接口（拿到每台吹风机的状态：空闲 / 使用中 / 故障）。
3. 下单 / 启动（余额启动或生成支付参数）接口。

### 4. 洗衣机 · 乐仔生活（Phase 3）

Phase 1 先采用一键直达入口；后续优先尝试抓取**只读接口**：
1. 所在校区/楼栋洗衣机列表接口（确认 URL、签名/Header 及每台机器的空闲状态与剩余倒计时字段）。
2. 评估是否存在强设备签名或防重放校验，再决定是否接入只读看板。
