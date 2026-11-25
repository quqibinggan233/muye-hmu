# 易校园接口对接手册（HMU 龙子湖）

**一份文档搞定全部：抓包环境、登录、签名、一卡通余额、浴室动态用水码。**

| 项 | 值 |
|---|---|
| 客户端 | 易校园 `cn.com.yunma.school.app` v7.9.2（versionCode 792，targetSdk 31） |
| 学校 | 河南牧业经济学院 · 龙子湖校区，`schoolCode = 10469` |
| 业务范围 | **只有**一卡通余额 + 浴室动态用水码（本校不收宿舍电费，不接电费） |
| 验证时间 | 2026-11-25，Android 15 root 模拟器 + mitmproxy，本人账号实测 |
| 状态 | 签名算法、余额、用水码 **全部打通**；仅「发送登录短信」需借助官方 App |

---

## 0. 一页速查

### 三个凭据（互不替代）

| 凭据 | 长什么样 | 干什么用 | 怎么来 |
|---|---|---|---|
| `token` | `<TOKEN>` | 余额等原生接口的 `token` 字段 | 登录响应 `data.token` |
| `uuToken` | `<UU_TOKEN>` | **签名密钥原料** | 登录响应 `data.uuToken`（静默登录不给） |
| `shiroJID` | `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` | H5 / 用水码的 Cookie | 原生写进 WebView Cookie，抓包直接读 |

### 三个接口

| 用途 | 接口 | 鉴权 |
|---|---|---|
| 一卡通余额 | `POST compus.xiaofubao.com/compus/user/getCardMoney` | `token` + `sign` |
| 浴室用水码 | `POST application.xiaofubao.com/app/hzsun/cloudwater/queryRanCode` | 仅 `Cookie: shiroJID` |
| 登录 | `POST compus.xiaofubao.com/login/doLoginByVerificationCode` | **不需要** `sign` |

### 错误码

| statusCode | 含义 | 怎么办 |
|---|---|---|
| `0` | 成功 | — |
| `204` | 请重新登录 | `shiroJID` / `token` 失效 |
| `219` | 鉴权失败（bizCode `00010`） | `sign` 或 `key` 算错 |
| `-1` | 参数异常 | 缺字段（`100000003` 缺参数 / `1000022` 参数格式） |

---

## 1. 抓包环境（怎么让 HTTPS 可见）

**不是证书固定。** App 只信任系统 CA 库，用户态装的 mitmproxy 证书不在 system store → 握手被拒（`TLS_FAILED_CLIENT`）。

```bash
# 1) 模拟器需可 root（google_apis 镜像，非 Play 镜像）
adb root

# 2) 计算 CA 文件名
HASH=$(openssl x509 -inform PEM -subject_hash_old -in mitmproxy-ca-cert.pem | head -1)

# 3) 系统 CA 目录是 tmpfs overlay，改成可写再拷贝
adb shell "mount -t tmpfs tmpfs /system/etc/security/cacerts"
adb shell "cp /apex/com.android.conscrypt/cacerts/* /system/etc/security/cacerts/"
adb push mitmproxy-ca-cert.pem /sdcard/${HASH}.0
adb shell "cp /sdcard/${HASH}.0 /system/etc/security/cacerts/${HASH}.0"

# 4) 【关键】SELinux 标签必须补，否则 system_server 崩溃循环
adb shell "chcon u:object_r:system_security_cacerts_file:s0 /system/etc/security/cacerts"
adb shell "chcon u:object_r:system_security_cacerts_file:s0 /system/etc/security/cacerts/*"

# 5) 重启框架，然后轮询到 activity service 起来（5~10 分钟）
adb shell stop; adb shell start
adb shell service check activity        # 直到输出 found:

# 6) 走代理
adb shell settings put global http_proxy 10.0.2.2:8888
```

验证：`adb shell mount | grep conscrypt` 有挂载，且 H5 与原生请求都能解出明文。

> 少做第 4 步 → `system_server` 抛 `ExceptionInInitializerError at SystemCertificateSource.getInstance`，
> 反复崩溃，只能 wipe 重装。

---

## 2. 登录

### 2.1 提交验证码（可用）

```http
POST https://compus.xiaofubao.com/login/doLoginByVerificationCode
Content-Type: application/json; charset=UTF-8
sign: <随便填；实测空串 / 伪造值都能过>
```

```json
{
  "appVersion": "740",
  "appAllVersion": "7.9.2",
  "appPlatform": "Android",
  "osType": "Android",
  "osVersion": "15",
  "brand": "google",
  "mobileType": "sdk_gphone64_arm64",
  "platform": "YUNMA_APP",
  "deviceId": "ym-<32位hex>",
  "clientId": "<任意字符串>",
  "mobilePhone": "138xxxxxxxx",
  "verificationCode": "<VERIFY_CODE>",
  "schoolCode": "10469",
  "token": "",
  "linkUserId": "",
  "invitationCode": "",
  "nt": 1795573312646
}
```

成功响应：

```json
{
  "statusCode": 0, "message": "操作成功", "success": true,
  "data": {
    "token":   "<TOKEN>",
    "uuToken": "<UU_TOKEN>",
    "id":      "<YM_ID>",
    "schoolCode": "10469"
  }
}
```

- `data.id` 就是后面所有接口要的 `ymId`
- 两个字段缺一不可，`uuToken` 缺了就没法签名

### 2.2 发送验证码（未解决）

```http
POST https://compus.xiaofubao.com/compus/user/sendLoginVerificationCode
```

必填且无法省略：

| 字段 | 来源 |
|---|---|
| `securityToken` | `POST /login/getSecurityToken` |
| `appSecurityToken` | 官方 native `NativeYischool.getAppToken(securityToken, ctx)` 生成 |
| `imageCaptchaValue` | 图形验证码 `/common/security/imageCaptcha` |
| `mobilePhone` / `deviceId` / `schoolCode` / `sendCount` | — |

去掉前两项 → `{"statusCode":-1,"message":"参数异常","bizCode":"100000003"}`。

**现状**：请在官方 App 点「获取验证码」，把 6 位码填进本项目 —— 提交这一步我们能自己做。
要全自动，得再复刻一次 `getAppToken`（RSA）+ 图形验证码识别。

### 2.3 登录态补充说明

- `POST /login/doLoginBySilent` 的 data 里**没有** `uuToken`，只有 `token`/`ymId`
- `uuToken` 不落盘（`UserInfoBean` 序列化时排除），全盘 grep 不到，只能在登录响应时接住
- 登录接口**不校验 `sign`**：用空 sign 打过去只报「验证码已失效」，说明已越过鉴权

---

## 3. 签名算法（核心）

```java
// ① 派生 HMAC 密钥
int a      = Integer.parseInt(ymId.substring(ymId.length() - 2, ymId.length() - 1)); // 倒数第 2 位
int b      = Integer.parseInt(ymId.substring(ymId.length() - 3, ymId.length() - 2)); // 倒数第 3 位
String S   = ymId.substring(a) + deviceId.substring(b) + uuToken;
String key = sha256_hex(S).substring(32);        // ★ 取哈希【后】32 位

// ② 拼待签串：按 key 字典序 → 取值 → 跳过空值 → trim → "|" 连接
String data = join("|", sortedValues(body));

// ③ 出签名：44 字符 Base64，NO_WRAP
String sign = Base64( HMAC_SHA256(key, data) );
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

### 已验证的性质

| 性质 | 结论 |
|---|---|
| 覆盖范围 | 只覆盖 body；换 URL / IP 不影响 |
| 时间绑定 | 不绑定：同 body 20 分钟后原样复放仍成功 |
| `nt` | 在 body 内，改 `nt` 签名必变 |
| `uuToken` | 强绑定：重新登录后旧 key 对旧会话 0/6 通过、新 key 对新会话 6/6 通过 |
| 是否常量 | 否（1561 条库内字符串穷举未命中） |
| 换 key 时机 | `uuToken` 一变就必须重算 |

### Kotlin 实现（可直接用）

```kotlin
object YixiaoyuanSigner {
    fun deriveKey(ymId: String, deviceId: String, uuToken: String): String {
        if (ymId.isBlank() || deviceId.isBlank() || uuToken.isBlank()) return ""
        val a = digitAt(ymId, ymId.length - 2)
        val b = digitAt(ymId, ymId.length - 3)
        val s = ymId.drop(a.coerceIn(0, ymId.length)) +
                deviceId.drop(b.coerceIn(0, deviceId.length)) + uuToken
        return MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            .substring(32)
    }

    fun signParams(params: Map<String, String>): String =
        params.toSortedMap().values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("|")

    fun sign(params: Map<String, String>, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal(signParams(params).toByteArray()))
    }

    private fun digitAt(s: String, i: Int) = if (i in s.indices) s[i].digitToIntOrNull() ?: 0 else 0
}
```

### Python 实现（可直接用）

```python
import hashlib, hmac, base64

def derive_key(ym_id, device_id, uu_token):
    a, b = int(ym_id[-2]), int(ym_id[-3])
    s = ym_id[a:] + device_id[b:] + uu_token
    return hashlib.sha256(s.encode()).hexdigest()[32:]

def sign_params(body):
    return "|".join(str(body[k]).strip() for k in sorted(body) if str(body[k]).strip() != "")

def sign(body, key):
    return base64.b64encode(
        hmac.new(key.encode(), sign_params(body).encode(), hashlib.sha256).digest()
    ).decode()
```

---

## 4. `shiroJID` 从哪来

**没有换票接口。** 原生打开「生活用水」页面时，把一个 UUID **直接写进 WebView CookieStore**，没有 `Set-Cookie`、没有 302 换票、`?token=` 也造不出来。

取法（唯一可行）：抓任意一条 `application.xiaofubao.com` 请求的请求头

```
Cookie: shiroJID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

- 也可从 root 下的 WebView cookie 库（SQLite）读
- 失效表现：`{"statusCode":204,"message":"请重新登录"}`（不是 401/403）
- 有效期未精确测定；实测同一登录态下跨天可用

> **`shiroJID` 与 `token`/`uuToken` 是两套独立凭据**：
> 用水码只要 `shiroJID`，余额只要 `token`+`uuToken`。任一方失效各自刷新。

---

## 5. 一卡通余额

```http
POST https://compus.xiaofubao.com/compus/user/getCardMoney
Content-Type: application/json; charset=UTF-8
sign: <见第 3 节>
```

```json
{
  "appVersion": "740",
  "platform": "YUNMA_APP",
  "deviceId": "ym-<32位hex>",
  "ymId": "<YM_ID>",
  "nt": 1795573313981,
  "token": "<登录返回的 token>"
}
```

响应：

```json
{"statusCode":0,"message":"操作成功","success":true,"data":"0"}
```

- **余额就是 `data` 本身**（字符串），不是对象里的某个字段
- 单位：**元**，如 `"12.30"`；App 内直接 `"¥" + data` 展示
- 同族：`/compus/user/getCardInfo`、`/compus/user/getMultiCardMoney`（本校返回非 JSON，未用）
- 消费明细：`/routeauth/auth/route/user/cardQuerynoPage`

---

## 6. 浴室动态用水码

```http
POST https://application.xiaofubao.com/app/hzsun/cloudwater/queryRanCode
Content-Type: application/x-www-form-urlencoded;charset=UTF-8
Cookie: shiroJID=<见第 4 节>

platform=YUNMA_APP
```

响应：

```json
{
  "statusCode": 0, "message": "操作成功", "success": true,
  "data": {
    "ranCode":        "<8位码>",
    "ranValidTime":   360,
    "ranValidTimes":  49,
    "ranIntervalTime":60,
    "ranGetTimes":    29,
    "ranDeviceAmount":15,
    "numLength":      "4",
    "longTerm":       "-1",
    "ranApplyTime":   " 2026-11-24 16:36:26",
    "sign":           "<SIGN_HEX>",
    "dictId":         "1"
  }
}
```

| 字段 | 含义 |
|---|---|
| `ranCode` | **用水码，8 位** |
| `ranValidTime` | 本次码有效期（秒） |
| `ranValidTimes` | 当日剩余可获取次数 |
| `ranIntervalTime` | 两次获取最小间隔（秒） |
| `ranGetTimes` | 当日已获取次数 |
| `ranDeviceAmount` | 可用户设备数 |
| `numLength` | **设备编号长度，不是码长**（最容易看错） |
| `longTerm` | `"1"` = 长期有效；其他 = 按天/分钟 |
| `sign` | 响应自带签名，本端无需校验 |

同族接口（同鉴权）：

| 用途 | 路径 |
|---|---|
| 获取 / 刷新 | `/app/hzsun/cloudwater/queryRanCode` |
| 申请（带设备名） | `/app/hzsun/cloudwater/insertRanCode`，body `unPayName=<设备名>` |
| 取消 | `/app/hzsun/cloudwater/cancelRanCode`，body `ranCode=` |
| 配置 | `/app/hzsun/cloudwater/getConfig` |
| 失败流水 | `/app/hzsun/cloudwater/queryFailedTrans` |

前端路由 `/water/zy/ranCode`，chunk `p__water__zy__ranCode.cc5092af.async.js`；UI 文案「我的用水码」「当日获取次数已用完」。

> **不接**：`com.klcxkj.zqxy` + `com.hzsun.water` 那套 `https://zyoffline.china-qzxy.cn/`、
> MQTT `tcp://zymq.china-qzxy.cn:1883`、`bathCode`/`bathpasswd` ——
> 需要 MQTT+BLE，且能直接开阀门。本项目只取「动态用水码」。

---

## 7. 公共字段与信封

多数 compus 接口通用：

```json
{
  "appVersion": "740",
  "appAllVersion": "7.9.2",
  "appPlatform": "Android",
  "platform": "YUNMA_APP",
  "osType": "Android",
  "osVersion": "15",
  "brand": "google",
  "mobileType": "sdk_gphone64_arm64",
  "deviceId": "ym-<32位hex>",
  "ymId": "<YM_ID>",
  "schoolCode": "10469",
  "nt": 1795573313981,
  "token": "<登录返回>"
}
```

`deviceId` 格式 `ym-` + 32 位十六进制，与账号绑定后**不要变**。

响应信封：

```json
{"statusCode":0,"message":"操作成功","data":{…},"success":true}
```

---

## 8. 排错表

| 症状 | 原因 | 解决 |
|---|---|---|
| mitmproxy 显示 `TLS_FAILED_CLIENT` | 只装了用户态 CA | 走第 1 节，装进系统 CA 并补 `chcon` |
| `system_server` 崩溃循环 `SystemCertificateSource` | 漏了 `chcon` | 补标签后 `stop; start`，否则只能 wipe |
| 所有原生接口 `219 鉴权失败` | `sign` 错 | 检查 key 是否取哈希**后** 32 位、body 是否含 `nt`/`token` |
| 余额接口 `204` | `token` 失效 | 重新登录换 `token` |
| 用水码 `204 / 请重新登录` | `shiroJID` 失效 | 重进官方「生活用水」页后重新读 Cookie |
| `-1 参数异常 100000003` | 缺必填字段 | 对照各节 body 补齐 |
| 拿不到 `uuToken` | 用了静默登录 | 必须走密码 / 短信验证码登录 |
| 用水码位数对不上 | 看了 `numLength` | 码长固定 8 位，`numLength` 是设备编号长度 |

---

## 9. 复现 checklist

- [ ] 模拟器 root（google_apis 镜像）
- [ ] 系统 CA 注入 + `chcon` + 重启框架
- [ ] 代理指向 mitmproxy，确认能解出 `compus` / `application` 明文
- [ ] 在官方 App 触发一次「余额查询」，抓到 `token` / `uuToken` / `ymId`
- [ ] 打开「生活用水 → 用水码」，抓到 `Cookie: shiroJID=…`
- [ ] 用第 3 节算法对任意一条已抓请求复算 `sign`，与抓包值比对一致
- [ ] 调 `getCardMoney` 返回 `statusCode 0`
- [ ] 调 `queryRanCode` 返回 8 位 `ranCode`

---

## 10. 安全提醒

- `token` / `uuToken` / `shiroJID` 等价于登录态，**不要提交进仓库、不要贴日志**
- 分享抓包记录前，把 `password`、验证码、手机号、`token`、`uuToken`、`shiroJID`、真实用水码全部替换为 `<REDACTED>`
- 本手册只覆盖**只读**能力（登录、查余额、查码）；充值、挂失、绑卡、开门阀一概未涉及

---

## 附：这些结论是怎么来的（备查）

1. **抓包**：系统 CA 注入后，全部 HTTPS 明文可见，共 214 条记录 / 43 个端点
2. **反编译**：APK 被网易易盾 + 爱加密加固，`classes.dex` 只有 stub 头，静态反编译必失败；
   改为从运行中进程的 `/proc/<pid>/mem` dump 出 **10 个业务 dex（82 MB，约 7.2 万类）**
3. **签名定位**：`BaseSimpleRepos.createAuthInterceptor()` → `NetworkUtil.handleSignData()`
   → `NativeYischool.getSignData(ymId, uuToken, map, ctx)`
   → `Sha256Util.getSignParams()` + `generateHmacSha256Base64()`
4. **native 解密**：`libyischool.so` 磁盘上代码段加密、ELF 头被抹；从内存 dump 各段拼回，
   验证解出的是标准 ARM64 序言，再用 capstone 反汇编 `getSignData`
5. **key 反推**：Frida 被易盾反调试拦死（attach 即被杀，改名换端口无效）；
   改为扫描进程堆取出全部 32 位十六进制串，逐个对已抓 `(body, sign)` 试算，
   命中唯一 key，再对 uuToken 附近内存做子串哈希找原像，得到 `ymId.slice + deviceId.slice + uuToken`
