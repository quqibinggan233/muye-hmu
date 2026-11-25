# 易校园接口规格草案（静态逆向产出 · v7.9.2）

> **样本**：`base.apk`，SHA-256 `04d066684fdda3b41d9bf9f401b57e39033d0153992f1e7247bf6a3f98b9295c`
> **来源**：工作区 `base.apk`（对应 `cn.com.yunma.school.app` v7.9.2 / versionCode 792）
> **方法**：DEX 字符串池静态提取（代码体被加固抽取，字符串池完整可读）
> **状态**：🟢 已确认（可直接编码） / 🟡 高置信推断（需一次抓包验证） / 🔴 未知（必须抓包）
> **脱敏**：本文档不含任何账号、密码、token、真实用水码

---

## 〇、加固情况说明（为什么是"字符串级"产出）

| 项 | 观测 | 含义 |
|---|---|---|
| `classes.dex` | 86,435,916 B，单 dex，magic `dex\n035` | 头部合法 |
| dex header | `string_ids_size=949`，而实际可读字符串 **1,611,812** 条 | **头部是 stub**，真实内容在 `0x650000`~`0x5100000` 数据区 |
| `jadx 1.5.6` / `apktool 3.0.3` | 均在早期中断，只解出 34 个类 | 两个独立工具同点失败 → 非内存问题 |
| `com.netease.nis` | 出现在已解出的 34 个类中 | **网易易盾加固** |
| `assets/.nesec_patch` | 8.6 MB | 易盾 patch 载荷 |
| `assets/ijm_lib/**/libexec.so` | 430 KB 真实 ELF | 爱加密（iJiami）组件 |
| `lib/arm64-v8a/*.so` | 全部 400~568 B | 全为**占位桩**，真实 native 代码不在 lib 下 |

**结论**：类定义、方法名、字段名、常量、URL、DTO `toString` 模板全部保留在字符串池中，因此**接口清单可以静态还原**；被抽取的是**方法体字节码**，所以 HTTP method、参数拼装、签名算法、envelope 解包逻辑需要一次运行时抓包补齐。

---

## 一、一卡通余额 🟢→🟡

### 1.1 端点

| 项 | 值 | 置信 |
|---|---|---|
| Host | `https://compus.xiaofubao.com` | 🟢 |
| Path | `/compus/user/getCardMoney` | 🟢 |
| 请求 DTO | `Lcom/yunma/baseextend/request/card/GetCardMoneyRequest;` | 🟢 |
| 响应 DTO | `Lcom/yunma/baseextend/bean/card/CardMoneyBean;`、`Lcom/yunma/baseextend/bean/card/Balance;` | 🟢 |
| Method | 待确认（同族多为 POST form） | 🔴 |
| 鉴权 | 待确认（`token` header 或 `Cookie: shiroJID`） | 🔴 |

### 1.2 字段

```
Balance(cardMoney=...)              → 卡余额，字段名 cardMoney
CardMoneyBean(availableBalance=...) → 可用余额，字段名 availableBalance
DigitWallet(availableBalance=...)
```

- 余额字段二选一或并存：**`cardMoney`（Balance）/ `availableBalance`（CardMoneyBean）**
- 单位（元 / 分）：🔴 待抓包确认。建议解析层统一按 `Decimal` 处理，用实测值写进单测锁定。
- 卡状态字段：🔴（`CardInfoBean` 有 `accountTitle` 等，但无明确 status 键名）

### 1.3 相邻可用端点（同族，一次抓包可顺带确认）

```
/compus/user/getUserById        /compus/user/getUserExt
/compus/user/device/queryInfo   /compus/user/device/save
/compus/user/checkPwd           /compus/user/bindCard
/compus/user/cardLoss           /compus/user/unLossCard
/compus/user/changeDefaultCard  /compus/user/getAuthToken   ← 见 §三
```

---

## 二、登录与短信验证码 🟢→🟡

### 2.1 端点清单

| 用途 | Path | DTO | 置信 |
|---|---|---|---|
| 取 RSA 公钥 | `/login/getPublicKey` | `PublicKeyRequest` / `PublicKeyBean(publicKey=)` | 🟢 |
| 发送登录短信验证码 | `/compus/user/sendLoginVerificationCode` | `SendSmsRequest` / `SendSmsBean(userExists=)` | 🟢 |
| 密码登录 | `/login/doLoginByPwd` | `LoginPwdRequest` | 🟢 |
| 验证码登录 | `/login/doLoginByVerificationCode` | `LoginRequest` | 🟢 |
| 静默/一键登录 | `/login/doLoginBySilent` | — | 🟢 |
| 学校选择 | `/login/selectSchool`、`/compus/school/getSchoolInfo` | `QuerySchoolRequest`/`SchoolBean` | 🟢 |
| 身份选择 | `/login/selectIdentity` | `SchoolIdentityRequest` | 🟢 |
| 账号鉴权 | `/login/accountAuth`、`/login/authLogin` | `AccountAuthRequest` | 🟢 |
| 短信校验 | `/compus/user/checkVerCode`-族（见下） | `CheckVerCodeRequest` | 🟡 |

短信验证码相关还有一族明确端点：
```
/compus/user/sendLoginVerificationCode
/compus/user/sendBindCardVerificationCode
/compus/user/sendOpenCardVerificationCode
/compus/user/sendValidCardVerificationCode
/compus/user/sendAppealCardVerificationCode
/compus/user/sendUpdatePwdVerificationCode
/compus/user/sendDeleteUserVerificationCode
/compus/user/sendActiveOneCardVerificationCode
/compus/user/sendChangeMobilePhoneVerificationCodeOne
/compus/user/sendChangeMobilePhoneVerificationCodeTwo
/compus/user/updateMobilePhoneCheckVerificationCode
```

### 2.2 密码传输 🟡 高置信 RSA

证据链：
- 存在 `/login/getPublicKey` + `PublicKeyBean(publicKey=...)`
- 存在方法名 `encryptPwd`、`encryptPwd2`
- 存在字符串 `RSA/ECB/PKCS1Padding`、`RSA/ECB/OAEPWithSHA-256AndMGF1Padding`

推断：`GET 公钥 → RSA(ECB/PKCS1Padding) 加密密码 → 提交`。**具体 padding 与 Base64/Hex 编码需抓包确认**（两个候选都在 App 内，无法静态区分）。

### 2.3 登录响应关键字段 🔴

`ymId` 与 `token` 的字段名未在字符串池中定位到赋值点（属方法体逻辑）。已知：
- `ymId` 作为字符串常量存在，且 `getYmId` 方法存在
- 响应 envelope 见 §六

---

## 三、H5 换票 `getAuthToken` 🟢→🟡

| 项 | 值 | 置信 |
|---|---|---|
| Path | `/compus/user/getAuthToken` | 🟢 |
| 关联常量 | `authToken`、`authTokenType`、`ticket` | 🟢 |
| 用途推断 | 用原生 `token`/`ymId` 换取 `application.xiaofubao.com` 所需的会话票据 | 🟡 |
| `shiroJID` 字面量 | **字符串池中不存在** | 🟢 |

**关键推论**：`shiroJID` 不是 App 里硬编码的 Cookie 名，而是 **`application.xiaofubao.com` 服务端 `Set-Cookie` 下发**。因此换票流程为：

```
原生登录 → token + ymId
        → POST /compus/user/getAuthToken  （带 token）
        → 返回 authToken / ticket
        → WebView 打开 https://application.xiaofubao.com/...?<票据参数>
        → 服务端 302 + Set-Cookie: shiroJID=...
        → 后续 H5 接口带 Cookie: shiroJID
```

🔴 待确认：`getAuthToken` 的请求参数名、返回字段名、票据以 **query 参数** 还是 **header** 透传给 H5。

---

## 四、浴室 6 位动态用水码 🟢 端点 / 🟡 字段

### 4.1 决定性发现：`useCode` 路径族

```
account/useCode/detail
account/useCode/status/update
account/useCode/autoCloseConfig/list
account/useCode/autoCloseConfig/save
```

配套字段（字符串池原文）：

```
useCodeRandom           ← 随机用水码（即 6 位动态码的候选）
useCodeExpiringTime     ← 过期时间 / 有效期
useCodeStatus           ← 用水码状态
useCodeAutoCloseConfig  ← 自动关闭配置
useCodeAutoCloseTip     ← 自动关闭提示
```

另有：
```
/center/water/config/getWaterConfig     ← 生活用水配置
```

**这与文档第 4 组诉求（6 位码 + 有效期 + 刷新）一一对应。** `useCodeRandom` 的位数是否为 6 位 🟡 待验证。

### 4.2 归属域名 🔴

`account/useCode/*` 在字符串池中**无 host 前缀**（无前导 `/`），属相对路径，随 Retrofit baseUrl 决定。候选：
- `application.xiaofubao.com`（生活用水 H5 后端，最可能）
- `compus.xiaofubao.com`

🔴 必须抓包判定。这是第 4 组唯一剩下的硬卡点。

### 4.3 另一条并存的"水控 SDK"路线（重要，勿混用）

App 内嵌第三方水控 SDK，与 `useCode` **不是同一套**：

| 项 | 值 |
|---|---|
| 包名 | `com.klcxkj.zqxy`（KLCXKJ-Water）、`com.hzsun.water` |
| 业务域 | `https://zyoffline.china-qzxy.cn/` |
| MQTT | `tcp://zymq.china-qzxy.cn:1883` |
| 关键接口 | `LogIn.aspx`、`GetWaterValves.aspx`、`OpenWaterValve.aspx`、`CloseWaterValve.aspx`、`GetWaterOerderInfo.aspx`、`/offlineOrder/water/downRate`、`/snOrder/downRate/snWater` |
| 响应类 | `FindBathCodeResponse`、`FindBathCodeStatusResponse`、`SetBathUseCodeResponse`、`QueryIsBathUseResponse`、`AppointmentBathCodeResponse`、`IssueBookingCodeResponse`、`OpenOrCloseCodeResponse`、`ModifiUseCodeResponse`、`CodeDetailResponse`、`CodeAutoCloseConfigResponse` |
| 数据类 | `BathCodeInfo`、`BathRoomInfo`、`BathWaterMeterInfo`、`WaterCodeListener` |
| 地址段 | `bathCode`、`bathpasswd` |

**判断**：这是「水表/阀控」侧的 SDK（蓝牙开阀、澡堂房间、预约码），字段风格 `bathCode`；而 `useCode*` 是易校园自己的「用水码」生命周期。本校只读数显码 → **优先走 `useCode`，不碰水控 SDK**（避免引入 MQTT 与蓝牙依赖）。

### 4.4 备用端点（若 `useCode` 不可用）

```
/compus/user/getQRCodeNew      → QRCodeNewBean(qrCode=...)
/compus/user/getQRCodeResult   → QRCodeResultBean
/compus/user/scanQrCode        → ScanResultBean
/compus/yard/getQrCode / getQrcodeStatus / getQrCode4Outer
/compus/command/getByCode      → CommandBean
```
🔴 这些是"付款码/扫码"，与 6 位用水码语义不同，仅作降级备选。

---

## 五、请求公共约定 🟡

### 5.1 已确认标识

```
source=YUNMA_APP          ← 与电费接口 platform=YUNMA_APP 同族
platform / appPlatform / clientType
ymId / schoolId / schoolIdentityType / cardNo / userId
deviceId / appVersion / osType
```

### 5.2 签名头 🟡

- 字符串池存在 `X-Sign`（唯一一处，无伴随常量）
- 存在 `MD5Util`、`HmacSHA256`、`AES/CBC/PKCS5Padding`、`RSA/ECB/PKCS1Padding`
- 🔴 **无法静态判定 `X-Sign` 是否参与所有请求**，也无法判定拼接顺序

**对策**：客户端把签名做成可插拔策略（`NoopSigner` 先顶上），抓包后按实测补 `Md5Signer`/`HmacSigner`，业务代码零改动。

### 5.3 UA / 设备指纹

`deviceId`、`osType`、`appVersion` 出现在字符串池且与请求头并列，🟡 高置信参与请求头。

---

## 六、响应 envelope 🟡

字符串池唯一确证的模板：

```json
{"code":"0","msg":"success"}
```

- 键：`code`（**字符串**，"0" 为成功）、`msg`、推断含 `data`
- 已知失败码：`statusCode == 204` 表示 `shiroJID` 无效（来自既有电费接口项目）
- 🔴 `code` 与 HTTP `statusCode` 的并存关系、业务错误码表 → 待抓包

---

## 七、仍需补的 4 个空位（一次抓包可全解）

| # | 空位 | 影响 | 补法 |
|---|---|---|---|
| 1 | 全部端点的 **HTTP method + Content-Type**（form / json） | 编码 | 抓包 |
| 2 | `GetCardMoneyRequest` / `LoginPwdRequest` / `useCode` 请求的**参数键名** | 编码 | 抓包 |
| 3 | `X-Sign` **是否存在/算法/拼接顺序** | 编码 | 抓包（若 header 里没有 x-sign 则免签） |
| 4 | `account/useCode/*` 的**归属域名** + `useCodeRandom` 位数 | 功能 | 抓包 |

抓包不需要脱壳：本文档 §〇 已证明 App **没有 pinning**，`res/4uC.xml` 的 `debug-overrides` 已含 `<certificates src="user" overridePins="true"/>`，把代理 CA 装进系统证书库（或开启 debuggable）即可解密。

---

## 八、证据索引

| 证据 | 位置 |
|---|---|
| DEX 字符串池提取 | `XYW/work/classes.dex`（1,611,812 条可读字符串） |
| smali（仅 stub 34 类） | `XYW/smali_out/smali/` |
| 加固载荷 | `smali_out/assets/.nesec_patch`、`smali_out/assets/ijm_lib/` |
| 网络配置 | `smali_out/res/xml/4uC.xml`（见交接文档 §1.1） |
