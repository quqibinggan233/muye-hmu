# 易校园接口：4 组难题的最终答案

> 对应文档：[YIXIAOYUAN_API_HANDOFF.md](YIXIAOYUAN_API_HANDOFF.md)
> 结论时间：2026-11-25｜环境：Android 15 模拟器（已 root）+ mitmproxy｜账号：本人账号实测

---

## 0. 前置：抓包为什么被挡，怎么破

**不是证书固定（pinning）。** 原文档推测的 `<certificates src="user" overridePins="true"/>` 只在
`<debug-overrides>` 下生效，结论没错，但**真正的原因更简单**：App 只信任系统 CA 库，
用户态装的 mitmproxy CA 不在 system store 里，所以握手直接被拒（`TLS_FAILED_CLIENT`）。

**破解方法（不需要重打包、不需要 Frida）**：把 CA 塞进系统信任库。

```bash
# 1) 模拟器必须可 root（google_apis 镜像，非 Play 镜像）
adb root
adb remount 2>/dev/null || true

# 2) 系统 CA 目录是 tmpfs overlay，先转成可写再拷贝
adb shell "mkdir -p /data/local/tmp/cacerts"
adb push mitmproxy-ca-cert.pem /data/local/tmp/cacerts/<HASH>.0
#    <HASH> = openssl x509 -inform PEM -subject_hash_old -in cert.pem | head -1

adb shell "mount -t tmpfs tmpfs /system/etc/security/cacerts"
adb shell "cp /apex/com.android.conscrypt/cacerts/* /system/etc/security/cacerts/"
adb shell "cp /data/local/tmp/cacerts/* /system/etc/security/cacerts/"

# 3) 关键一步：SELinux 标签必须补上，否则 system_server 会崩溃循环
adb shell "chcon u:object_r:system_security_cacerts_file:s0 /system/etc/security/cacerts"
adb shell "chcon u:object_r:system_security_cacerts_file:s0 /system/etc/security/cacerts/*"

# 4) 重启框架
adb shell stop; adb shell start
#    等 5~10 分钟，用 adb shell service check activity 轮询到 found: 再继续

# 5) 设置代理
adb shell settings put global http_proxy 10.0.2.2:8888
```

**踩过的坑**：漏掉第 3 步的 `chcon` → `system_server` 进
`ExceptionInInitializerError at SystemCertificateSource.getInstance` 崩溃循环，只能 wipe。

验证：`adb shell mount | grep conscrypt` 能看到挂载，H5 与原生请求都能解出明文。

---

## 1. 发送短信验证码 & 登录

### 1.1 发送验证码 —— ⚠️ 未解决（唯一遗留）

```markdown
- Method & URL: POST https://compus.xiaofubao.com/compus/user/sendLoginVerificationCode
- 必填且无法省略:
    securityToken      ← 来自 /login/getSecurityToken
    appSecurityToken   ← 官方 native NativeYischool.getAppToken(securityToken, ctx) 生成
    imageCaptchaValue  ← 图形验证码（/common/security/imageCaptcha）
    mobilePhone, deviceId, schoolCode, sendCount
- 实测: 去掉 securityToken / appSecurityToken → {"statusCode":-1,"message":"参数异常","bizCode":"100000003"}
```

即：**发短信这一步被 native 兜住了**，纯 Kotlin 复刻需要再造一次
`getAppToken`（RSA 加密）并接图形验证码识别。

### 1.2 登录提交 —— ✅ 完全可用，且**不校验签名**

```markdown
- Method & URL: POST https://compus.xiaofubao.com/login/doLoginByVerificationCode
- Headers: Content-Type: application/json; charset=UTF-8
           sign: <随便填，实测空串或伪造值都能过>
- Request Body:
  {
    "appVersion": "740", "appAllVersion": "7.9.2", "appPlatform": "Android",
    "osType": "Android", "osVersion": "15", "brand": "google",
    "mobileType": "sdk_gphone64_arm64", "platform": "YUNMA_APP",
    "deviceId": "ym-<32位hex>", "clientId": "<任意>",
    "mobilePhone": "138xxxxxxxx", "verificationCode": "<VERIFY_CODE>",
    "schoolCode": "10469", "token": "", "linkUserId": "", "invitationCode": "",
    "nt": 1795573312646
  }
- Response Body:
  {
    "statusCode": 0, "message": "操作成功", "success": true,
    "data": {
      "token":   "<TOKEN>",   ← 会话令牌
      "uuToken": "<UU_TOKEN>",   ← 签名密钥原料【关键】
      "id":      "<YM_ID>",                ← 就是 ymId
      "schoolCode": "10469", ...
    }
  }
```

实测证据：把 `sign` 换成空串 / 伪造 Base64，服务器仍只回「验证码已失效，请重新获取」，
说明**已经越过鉴权、只卡在验证码**。

> **重要**：`uuToken` **只在密码/短信登录的响应里下发**。
> `POST /login/doLoginBySilent`（静默登录）的 data 里**没有** `uuToken`，
> 且 `UserInfoBean` 序列化时把它排除了，全盘 grep 不到 —— 只能登录时截获并自行缓存。

---

## 2. `shiroJID` 不是"换票"得来的

原文档猜的是"用 token/ymId 换 shiroJID 的接口或 302 跳转"——**都不存在**。

**真实机制**：原生在打开「生活用水」`WebActivity` 时，把服务端早就下发好的一个 UUID
**直接写进 WebView 的 CookieStore**，没有任何 `Set-Cookie`、没有换票接口、`?token=` 也造不出 Cookie。

```markdown
- 获取方式（唯一可行）：把它当 Cookie 读出来
  - 抓包时看任意一条 application.xiaofubao.com 请求的请求头：
      Cookie: shiroJID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  - 或从 App 的 WebView cookie 库里取（root，cookie 存 SQLite）
- 有效期：未精确测定；实测同一登录态下跨天可用
- 失效表现：{"statusCode":204,"message":"请重新登录"}
- 注意：缺失或伪造 → 同样 204，不会 401/403
```

对本项目的影响：**shiroJID 与 token/uuToken 是两套独立凭据**，
用水码只需要 `shiroJID`，余额只需要 `token`+`uuToken`。

---

## 3. 一卡通余额 —— ✅ 已打通

```markdown
- Method & URL: POST https://compus.xiaofubao.com/compus/user/getCardMoney
- Headers:
    Content-Type: application/json; charset=UTF-8
    sign: <见第 5 节算法>
- Request Body:
  {
    "appVersion": "740", "platform": "YUNMA_APP",
    "deviceId": "ym-<32位hex>",
    "ymId": "<YM_ID>",
    "nt": 1795573313981,
    "token": "<登录返回的 token>"
  }
- Response Body:
  {
    "statusCode": 0, "message": "操作成功", "success": true,
    "data": "0"          ← 余额，字符串，单位【元】（如 "12.30"）
  }
```

- 余额字段就是 `data` 本身（`BaseResponse<String>`），不是对象里的字段
- 单位是**元**，App 里直接 `"¥" + data` 展示
- 同族接口：`/compus/user/getCardInfo`、`/compus/user/getMultiCardMoney`（本校返回非 JSON，未启用）
- 消费明细：`/routeauth/auth/route/user/cardQuerynoPage`

---

## 4. 浴室动态用水码 —— ✅ 已打通（**是 8 位，不是 6 位**）

```markdown
- Method & URL: POST https://application.xiaofubao.com/app/hzsun/cloudwater/queryRanCode
- Headers:
    Content-Type: application/x-www-form-urlencoded;charset=UTF-8
    Cookie: shiroJID=<见第 2 节>      ← 只需要这一个，不要 sign
- Request Body:  platform=YUNMA_APP
- Response Body:
  {
    "statusCode": 0, "message": "操作成功", "success": true,
    "data": {
      "ranCode":        "<8位码>",      ← 用水码，8 位
      "ranValidTime":   360,             ← 本次码有效期（秒）
      "ranValidTimes":  49,              ← 当日剩余可获取次数
      "ranIntervalTime":60,              ← 两次获取最小间隔（秒）
      "ranGetTimes":    29,              ← 当日已获取次数
      "ranDeviceAmount":15,              ← 该账户可用户设备数
      "numLength":      "4",             ← 【不是码长】设备编号长度，别混用
      "longTerm":       "-1",            ← "1"=长期有效；其他=按天/分钟
      "ranApplyTime":   " 2026-11-24 16:36:26",
      "sign":           "<SIGN_HEX>",     ← 响应自带签名，本端无需校验
      "dictId":         "1"
    }
  }
```

同族接口（同鉴权方式）：

| 用途 | 路径 |
|---|---|
| 获取/刷新码 | `/app/hzsun/cloudwater/queryRanCode` |
| 申请用水（带设备名） | `/app/hzsun/cloudwater/insertRanCode`（`unPayName=<设备名>`） |
| 取消用水码 | `/app/hzsun/cloudwater/cancelRanCode`（`ranCode=`） |
| 配置 | `/app/hzsun/cloudwater/getConfig` |
| 失败流水 | `/app/hzsun/cloudwater/queryFailedTrans` |

前端路由 `/water/zy/ranCode`，对应 chunk `p__water__zy__ranCode.cc5092af.async.js`。
UI 文案：「我的用水码」「当日获取次数已用完」。

**明确不接**（避免误伤）：`com.klcxkj.zqxy` + `com.hzsun.water` 那套
`https://zyoffline.china-qzxy.cn/`、MQTT `tcp://zymq.china-qzxy.cn:1883`、`bathCode`/`bathpasswd`
—— 那是另一个厂商的水控栈，需要 MQTT+BLE，能直接开阀门。
本项目只取「动态用水码」这一条。

---

## 5. `sign` 算法（核心答案）

算法藏在加固库 `libyischool.so` 的 JNI 里（磁盘上是加密的，需从运行时内存 dump 才能反汇编），
最终完整还原如下：

```java
// 1) 派生 HMAC 密钥（依赖登录返回的 uuToken）
int a   = Integer.parseInt(ymId.substring(ymId.length() - 2, ymId.length() - 1)); // 倒数第 2 位
int b   = Integer.parseInt(ymId.substring(ymId.length() - 3, ymId.length() - 2)); // 倒数第 3 位
String S   = ymId.substring(a) + deviceId.substring(b) + uuToken;
String key = sha256_hex(S).substring(32);   // 取哈希【后】32 位十六进制字符

// 2) 拼接待签串（复刻 Sha256Util.getSignParams）
//    参数按 key 字典序排列 → 取值 → 跳过空值 → trim → 用 "|" 连接
String data = join("|", sortedValues(body));

// 3) 计算签名
String sign = Base64( HMAC_SHA256(key, data) );   // 44 字符，NO_WRAP
```

**算例（合成数据，非真实账号）**

```
ymId     = 1234567890123456789            → a = 8, b = 7
deviceId = ym-0123456789abcdef0123456789abcdef
uuToken  = 0123456789abcdef0123456789abcdef

ymId.substring(8)       = 90123456789                              (11 位)
deviceId.substring(7)   = 456789abcdef0123456789abcdef             (28 位)
uuToken                 = 0123456789abcdef0123456789abcdef         (32 位)

S = 90123456789 + 456789abcdef0123456789abcdef + 0123456789abcdef0123456789abcdef

sha256 = b129668ce5f3e90b2da90d14d8a1fa7d │ 6fe0c3b95d2c4d9071a7696e55f6be8c
key    =                                  └─ 取这段 32 位

data = 740|ym-0123456789abcdef0123456789abcdef|1700000000000|YUNMA_APP|tok-abc123|1234567890123456789
sign = Base64(HMAC-SHA256(key, data)) = pHGG7NtQsud6YPmtzNiusp2A5/N6EsNsrLsDCUnLjIY=
```

**已验证的性质**

| 性质 | 结论 |
|---|---|
| 覆盖范围 | 只覆盖 body；换 URL、换 IP 不影响 |
| 时间绑定 | 不绑定（同一 body 20 分钟后原样复放仍成功） |
| 与 nt 的关系 | `nt` 在 body 里，改 `nt` 签名必变 |
| 与 uuToken 的关系 | 强绑定：重新登录后旧 key 对旧会话 0/6 通过、新 key 对新会话 6/6 通过 |
| 是否常量 | 否（拿 1561 条库内字符串穷举未命中） |
| 登录接口是否校验 | 否（空 sign 也能过） |

**请求公共字段**（几乎所有 compus 接口通用）

```json
{
  "appVersion": "740",
  "appAllVersion": "7.9.2",
  "appPlatform": "Android",
  "platform": "YUNMA_APP",
  "osType": "Android", "osVersion": "15",
  "brand": "google", "mobileType": "sdk_gphone64_arm64",
  "deviceId": "ym-<32位hex>",
  "ymId": "<YM_ID>",
  "schoolCode": "10469",
  "nt": 1795573313981,
  "token": "<登录返回>"
}
```

**响应信封**

```json
{"statusCode":0,"message":"操作成功","data":{…},"success":true}
```

| statusCode | 含义 | 处理 |
|---|---|---|
| `0` | 成功 | — |
| `204` | 请重新登录 | shiroJID / token 失效，需重新登录 |
| `219` | 鉴权失败（bizCode `00010`） | sign 或 key 不对 |
| `-1` | 参数异常（bizCode `100000003` / `1000022`） | 缺字段 |

---

## 6. 会期与刷新策略（原文档第 3 组核查项）

| 凭据 | 来源 | 要不要重新登录 |
|---|---|---|
| `token` | 登录响应 | 204/219 时重新登录 |
| `uuToken` | **仅**密码/短信登录响应 | 同上，且换 key 必须重算 |
| `shiroJID` | 原生写入 WebView Cookie | 204 时重新进「生活用水」页面刷新 |

**没有**「用 token 无感换 shiroJID」的接口——两者独立。
实践建议：登录成功就把 `token`/`uuToken` 加密落盘；`shiroJID` 单独存；任一方失效各自触发对应刷新。

---

## 7. 给复现者的三个硬坑

1. **`uuToken` 拿不到就没法签名**，而它不落盘、静默登录不给。要么登录时截获，要么别做余额。
2. **`numLength` ≠ 码长**。用水码是 8 位，原文档写的 6 位是错的，`numLength:"4"` 指设备编号长度。
3. **`sign` 的 key 不是常量、也不是 uuToken 直接哈希**，而是
   `sha256(ymId.slice + deviceId.slice + uuToken)` 的**后半段**——
   取前 32 位会一直 219，这是最容易卡住的地方。

---

## 8. 附：可直接运行的验证脚本

`XYW/tools/probe_sign.py`（已实现完整算法）

```bash
python3 probe_sign.py \
  --token <token> --uutoken <uuToken> \
  --ymid <YM_ID> \
  --deviceid ym-<32位hex> \
  --call /compus/user/getCardMoney
```

输出：

```
key = <KEY>
{"statusCode": 0, "message": "操作成功", "data": "0", "success": true}
```
