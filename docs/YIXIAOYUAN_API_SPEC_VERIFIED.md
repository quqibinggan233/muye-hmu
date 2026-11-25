# 易校园接口实测规格（抓包验证版）

> **样本**：`base.apk` v7.9.2（versionCode 792，targetSdk 31）
> **环境**：Android 15 / API 35 `google_apis` arm64 模拟器（`hmu_capture`），已 root
> **方法**：系统级 CA 注入 + mitmproxy 全量解密 + H5 静态资源直取 + 运行时 dex 内存转储
> **状态**：🟢 实测通过 / 🟡 已定位待补参数 / 🔴 阻塞
> **脱敏**：token、ymId、shiroJID、用水码均为占位符

---

## 一、抓包环境（可复现）

### 1.1 为什么 App 能解密
文档 §1.1 的判断被实测证实：**App 未做 certificate pinning**，只是 `<base-config>` 不含 `<certificates src="user"/>`。把代理 CA 注入**系统证书库**即可解密，无需改包、无需 Frida。

### 1.2 Android 15 的关键坑：conscrypt APEX + SELinux
Android 14+ 的系统 CA 已从 `/system/etc/security/cacerts` 迁到 `/apex/com.android.conscrypt/cacerts`（由 tmpfs 覆盖注入）。**注入后必须修正 SELinux 标签**，否则 `SystemCertificateSource` 静态初始化失败，`system_server` 会 FATAL 崩溃循环：

```
异常：java.lang.ExceptionInInitializerError
     at android.security.net.config.SystemCertificateSource.getInstance
```

正确步骤：

```bash
adb root && adb remount
# 1. 备份 + 计算哈希
H=$(openssl x509 -inform PEM -subject_hash_old -in mitmproxy-ca-cert.pem | head -1)
adb shell "mkdir -p /data/local/tmp/cacerts && cp -r /apex/com.android.conscrypt/cacerts/. /data/local/tmp/cacerts/"
adb push mitmproxy-ca-cert.pem /data/local/tmp/cacerts/$H.0
# 2. 记录原目录/文件的 SELinux 上下文（关键）
CTX=u:object_r:system_security_cacerts_file:s0
# 3. tmpfs 覆盖 + 回拷 + 打标签
adb shell "mount -t tmpfs tmpfs /apex/com.android.conscrypt/cacerts \
  && cp /data/local/tmp/cacerts/* /apex/com.android.conscrypt/cacerts/ \
  && chcon $CTX /apex/com.android.conscrypt/cacerts \
  && chcon $CTX /apex/com.android.conscrypt/cacerts/*"
# 4. 重启框架让 zygote 继承挂载
adb shell stop && adb shell start
# 5. 设代理
adb shell settings put global http_proxy 10.0.2.2:8888
```

### 1.3 内存读取需要关 SELinux
从 `/proc/<pid>/mem` 转储运行时 dex 时，`Enforcing` 下 `dd` 返回空：

```bash
adb shell setenforce 0          # 否则 /proc/pid/mem 读不到
# 注意：设备 shell 是 32 位算术，偏移必须落在主机算好写成十进制
# 且必须用 bs=4096 且 4096 对齐，bs=1M 的 skip 会读出全 0
```

---

## 二、原生接口（`https://compus.xiaofubao.com`）

### 2.1 请求公共约定 🟢 实测

- **方法**：全部 `POST`
- **Content-Type**：`application/json; charset=UTF-8`
- **公共 Body 字段**（扁平 JSON，无嵌套）：

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
  "clientId": "<15位随机串>",
  "nt": 1795519289418,
  "token": "<32位hex>",
  "ymId": "<19位数字>",
  "schoolCode": "10469"
}
```

- **响应 envelope**（统一）：

```json
{ "statusCode": 0, "message": "操作成功", "data": {...}, "success": true }
```

| statusCode | message | 含义 |
|---|---|---|
| 0 | 操作成功 | 成功 |
| 219 | 鉴权失败（bizCode `00010`） | `sign` 缺失/错误 |
| -1 | 参数异常（bizCode `100000003`） | 参数缺失 |

### 2.2 `sign` 头 ✅ 已完整还原（2026-11-25 实测通过）

```java
a   = Integer.parseInt(ymId.substring(len-2, len-1));   // ymId 倒数第 2 位
b   = Integer.parseInt(ymId.substring(len-3, len-2));   // ymId 倒数第 3 位
S   = ymId.substring(a) + deviceId.substring(b) + uuToken;
key = Sha256Util.encrypt(S).substring(32);              // sha256-hex 的【后】32 位

data = Sha256Util.getSignParams(body);                  // 排序取值 '|' 连接
sign = Base64( HMAC_SHA256(key, data) );
```

实机示例（脱敏占位）：

```
ymId     = <YM_ID>      → a=3, b=9
S        = <YM_ID_TAIL> + <DEVICE_ID_TAIL> + <UU_TOKEN>
sha256   = <SHA256_HIGH_32> <KEY>
key      = <KEY>        ← 取后 32 位
```

**要求**
- `uuToken` 只在**密码/短信登录**的响应里下发，静默登录不返回；登录后必须缓存
- 该 key **随 uuToken 变化**：实测同一设备重新登录后，旧 key 对旧会话请求 6/6 不匹配、新 key 对新会话 6/6 匹配
- `sign` 只覆盖 body（不绑时间、URL、IP）：同 body 20 分钟后原样复放仍成功；只改 `nt` 签名必变

**实测调用**

```
POST /compus/user/getCardMoney   → {"statusCode":0,"data":"0"}      # 余额，字符串，单位元
POST /compus/user/getUserExt     → {"statusCode":0,...}
```

可直接用 `XYW/tools/probe_sign.py` 复算与调用：

```bash
python3 probe_sign.py --token <token> --uutoken <uuToken> \
    --ymid <YM_ID> --deviceid ym-xxxxxxxx
```

> 还原过程备注：`libyischool.so` 为加固（磁盘上的代码段加密、ELF 头被抹），
> 需从运行时内存 dump 并拼回；`uuToken` 与 `sign` 均不落盘，只能从内存/抓包取。


### 2.3 登录与会话 🟢 路径 / 🔴 签名

```
POST /login/doLoginBySilent            静默登录（已登录态下可用）
POST /login/doLoginByPwd               密码登录（密码 RSA 加密，公钥取自 /login/getPublicKey）
POST /login/doLoginByVerificationCode  短信验证码登录
POST /compus/user/sendLoginVerificationCode  发送短信验证码
POST /compus/user/getAuthToken         取 H5 会话票据
```

`doLoginBySilent` 实测响应 `data` 关键字段：
`id`(即 ymId) / `schoolCode` / `schoolName` / `userName` / `userType` / `mobilePhone` / `jobNo` / `cardPhone` / `bindCardStatus` / `signType` / `eacctStatus`

### 2.4 一卡通余额 🟡 路径确定 / 🔴 需签名

```
POST https://compus.xiaofubao.com/compus/user/getCardMoney
Body: 公共字段（§2.1）
响应 data: cardMoney / availableBalance（字段名已由 DTO 确认）
```
⚠️ 实测：无签名 → `219`。**抓包未拿到该接口的明文**，因为本地已有登录态，余额入口走的是
`/routeauth/auth/route/user/cardQuerynoPage`（明细），其 `afterMon` 即交易后余额。

**替代方案（已实测可用，无需签名）**：`cardQuerynoPage` 的 `afterMon` 字段可推算余额——
但它同样需要签名，所以不构成规避。

---

## 三、H5 接口（`https://application.xiaofubao.com`）🟢 全部实测通过

### 3.1 鉴权：只有 Cookie，无签名

```
Cookie: shiroJID=<uuid>            ← 由原生侧写入 WebView CookieStore
Content-Type: application/x-www-form-urlencoded;charset=UTF-8
X-Requested-With: XMLHttpRequest
Body 例: platform=YUNMA_APP
```

- `shiroJID` 是 **UUID** 格式，非服务端 `Set-Cookie` 下发（实测无 Set-Cookie）
- 无 cookie 或无会话 → `{"statusCode":204,"message":"请重新登录"}`（与交接文档已知的 204 一致）

### 3.2 浴室用水码（原文档第 4 组）🟢 已打通

**页面路由**：`/water/zy/ranCode`（chunk：`p__water__zy__ranCode.cc5092af.async.js`）

| 用途 | 接口 |
|---|---|
| 查询当前用水码 | `POST /app/hzsun/cloudwater/queryRanCode` |
| 生成 / 刷新用水码 | `POST /app/hzsun/cloudwater/insertRanCode`，body `unPayName=<设备名>` |
| 取消用水码 | `POST /app/hzsun/cloudwater/cancelRanCode`，body `ranCode=<当前码>` |
| 用水配置 | `POST /app/hzsun/cloudwater/getConfig` |
| 失败流水 | `POST /app/hzsun/cloudwater/queryFailedTrans` |

`queryRanCode` 实测响应：

```json
{ "statusCode": 0, "message": "操作成功", "success": true,
  "data": {
    "ranCode": "<8位码>",          // ← 当前用水码
    "ranValidTime": 360,            // 有效期数值
    "ranValidTimes": 49,
    "ranIntervalTime": 60,          // 刷新/获取间隔（秒）
    "ranGetTimes": 29,              // 当日剩余获取次数
    "numLength": "4",
    "ranApplyTime": " 2026-11-24 16:36:26",
    "ranDeviceAmount": 15,
    "sign": "<SIGN_HEX>",
    "dictId": "1",
    "longTerm": "-1"                // 1=长期有效 / 0=按 ranValidTime / -1=其他
  } }
```

UI 取值逻辑（反编译确认）：
- 有效期文案：`1 === longTerm ? "长期有效" : "有效期{ranValidTime}{ 0===longTerm?"天":"分钟" }"`
- `ranGetTimes <= 0` 时禁用刷新，提示「当日获取次数已用完」
- `ranIntervalTime` 作为刷新倒计时

> ⚠️ **对交接文档的修正**：本校实测用水码是 **8 位数字**（`ranCode`），不是假设的 6 位。
> `numLength:"4"` 是设备号位数，与码长度无关。

### 3.3 扫码用水（开阀）接口族（备查，App 不接）

```
/app/hzsun/cloudwater/openValueOnline        /app/hzsun/cloudwater/openValueBefore
/app/hzsun/cloudwater/beforeOpenValueOnline /app/hzsun/cloudwater/openValueByBluetooth
/app/hzsun/cloudwater/getOnlineOrderStatus  /app/hzsun/cloudwater/queryOrderStatus
/app/hzsun/cloudwater/getListBySeqNo        /app/hzsun/cloudwater/reopenOrCloseOrder
/app/hzsun/cloudwater/dealFailedTransWithUrl
```

---

## 四、与交接文档的差异汇总

| 交接文档假设 | 实测结果 |
|---|---|
| 6 位动态用水码 | **8 位**（`ranCode`），有效期由 `ranValidTime`/`longTerm` 决定 |
| 用水码在 `application.xiaofubao.com` + `?ymId=&platform=` 风格 | 域名正确，但**无需 ymId query**，仅需 `Cookie: shiroJID`，body 为 form |
| 余额与登录都在 compus，靠 token | 正确，但**每个请求都必须带 `sign` 头**（文档未提及） |
| 可能存在 `pin-set` 之外的阻断 | 无 pinning，纯信任配置问题，系统 CA 即可破 |
| 疑有动态 sign | **确实存在**，且只覆盖 body，不绑定时间 |

---

## 五、剩余待办（按依赖排序）

| # | 任务 | blocking |
|---|---|---|
| 1 | 解出 `sign` 算法/密钥 | 需反编译加固后的业务 dex（`com.yunma.*`，约 7.8 万类） |
| 2 | `getAuthToken` 实测 | 依赖 1 |
| 3 | `getCardMoney` 实测（单位元/分） | 依赖 1 |
| 4 | token/shiroJID 有效期 | 依赖 1 |

**解 sign 的两条已验证可行路线**：

1. **补 jadx 内存/换工具**：业务 dex 仍在内存中；已确认的转储方法（§1.3）可用，
   但 82 MB dex 超出本机 jadx 堆上限。用 `dex2jar + cfr` 或 16 GB 机器可解。
2. **Frida 通用 hook**：`javax.crypto.Mac.doFinal` / `MessageDigest.digest` 打印入参与结果，
   即可反推 key 与消息拼接。需 `frida-server`（arm64）——本机 GitHub release 下载被阻断，
   需换镜像源。

**降级方案（今日即可交付）**：只接 H5 侧（用水码 + 钱包），一卡通余额沿用现有手动录入；
或在 App 内引导用户从易校园官方 App 粘贴一次 `shiroJID`。

## 七、实现状态（2026-11-25）

| 文件 | 说明 |
|---|---|
| `data/yixiaoyuan/YixiaoyuanSigner.kt` | 签名算法：`deriveKey` / `signParams` / `sign` |
| `data/yixiaoyuan/YixiaoyuanCredential.kt` | 凭据模型（token / uuToken / ymId / deviceId）+ 结果类型 |
| `data/yixiaoyuan/YixiaoyuanClient.kt` | compus 接口：验证码登录、一卡通余额（带签名） |
| `data/yixiaoyuan/YixiaoyuanWaterClient.kt` | 浴室用水码（H5 + shiroJID，无需签名） |
| `data/campus/CampusLifeStore.kt` | 新增 `yixiaoyuanCredential()` / `saveYixiaoyuanCredential()` / `yixiaoyuanDeviceId()`（加密存储） |
| `ui/life/LifeViewModel.kt` | `refreshShowerCode()` 改为真实拉取；新增 `loginYixiaoyuan()` / `refreshCardBalance()` |
| `test/.../YixiaoyuanSignerTest.kt` | 6 个用例（合成黄金向量，不含真实凭据） |
| `test/.../YixiaoyuanClientTest.kt` | 7 个用例（MockWebServer，校验 body / sign / 状态码映射） |

全量单测：190 个通过；`assembleDebug` 通过。

**验证方式**：签名与真实服务器对撞通过（`getCardMoney` → `statusCode 0`），
黄金样本为合成数据，仓库内不含任何真实 token / uuToken / 手机号。

### 仍待实现

1. **发送登录短信**：`/compus/user/sendLoginVerificationCode` 需要官方 native
   `getAppToken` 生成的 `securityToken` / `appSecurityToken` + 图形验证码。
   当前流程需在官方 App 内获取验证码后填入本项目（提交这一步服务端不校验签名，已实测）。
2. **UI 接入**：`YixiaoyuanConfigDialog` 增加「手机号 + 验证码登录」与「刷新余额 / 用水码」按钮。
