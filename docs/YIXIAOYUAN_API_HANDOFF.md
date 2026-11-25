# 易校园接口排查总结与对接清单（HMU 龙子湖校区）

> **适用版本**：易校园 `cn.com.yunma.school.app` `v7.9.2`（`versionCode=792`，`targetSdkVersion=31`）  
> **学校业务范围**：河南牧业经济学院 · 仅需 **一卡通余额** 与 **浴室 6 位动态用水码**（本校不收取宿舍电费，**禁止接入宿舍电费 UI**）

---

## 一、抓包受阻原因与实测现象

### 1.1 系统证书阻断机制（AndroidManifest / `networkSecurityConfig`）
从安装包 `AndroidManifest.xml` 与声明的资源文件 `res/4uC.xml` 实测确认：
- `<base-config cleartextTrafficPermitted="true"/>`：**未配置 `<certificates src="user"/>`**，也未声明 `<pin-set>`；
- `<certificates src="user" overridePins="true"/>` 仅写在 `<debug-overrides>` 节点下。
- 按照 Android 官方网络安全机制，`<debug-overrides>` 仅在 `android:debuggable="true"` 时生效；正式签名包会完全忽略用户在 Android 7+ 安装的用户态 CA 证书。因此，在**非 Root / 不做系统注入或重打包**的 Android 15 模拟器环境下，用户态抓包代理的 HTTPS 握手会被直接拒绝。

### 1.2 本次 `mitmdump`（`10.0.2.2:8888`）实测结果
在已登录账号的模拟器上依次触发只读操作后，观测到以下真实行为：

1. **「余额查询」（`BaseStandardActivity`）**
   - 发起目标：`compus.xiaofubao.com`（原生客户端直连）
   - 失败现象：`TLS_FAILED_CLIENT`（`The client does not trust the proxy's certificate`）
2. **「生活用水」（`com.yunma.baseextend.ui.WebActivity`）**
   - 页面形态：内置 H5 页面，无代理时实测可见 `收藏设备`、`附近设备`、`预设金额`、`钱包余额`、`扫码用水`、`账单查询`、`用水码` 入口。
   - 挂代理目标：`compus.xiaofubao.com` + `application.xiaofubao.com`
   - 失败现象：`TLS_FAILED_CLIENT`，同时 `WebActivity` 弹出「安全警告（该链接的安全证书存在问题）」。
3. **明文流量**
   - 仅第三方广告 SDK 的明文 `HTTP`（如 `ad.shunchangzhixing.com`、`report.shunchangzhixing.com`）可通过代理。

---

## 二、GitHub 开源项目排雷结论

| 仓库 | 协议 | 与本项目兼容性 | 实际覆盖范围 |
| :--- | :--- | :--- | :--- |
| `FoskyTech/E-Campus-Electricity` | **MIT** | 兼容 | 仅包含电费接口：`POST https://application.xiaofubao.com/app/electric/{getCoutomConfig\|queryArea\|queryBuilding\|queryFloor\|queryRoom\|queryRoomSurplus}?ymId=...&platform=YUNMA_APP`（携带 `Cookie: shiroJID=...`；已知 `statusCode=204` 表示 `shiroJID` 无效）。**不含登录、一卡通余额与生活用水码**。 |
| `FoskyTech/e-campus-login` | **AGPL-3.0** | **不兼容（禁止引入）** | README 说明登录后返回 `ymId`（接口返回字段 `id`）与 `token`，**不含余额查询与生活用水码接口**。 |
| 其余社区项目（`ECampusElectricity` 等） | MIT 等 | 仅参考思路 | 均要求人工抓包获取 `shiroJID` 和 `ymId`，未发现公开可用的动态 6 位用水码或余额接口实现。 |

---

## 三、待你自行抓包补齐的 4 组接口（按此模板填写即可直接回填代码）

> **安全提醒**：分享或归档抓包模板前，请务必将所有真实 `password`、验证码、手机号、`token`、`shiroJID`、学号及真实 6 位用水码替换为 `<REDACTED>`。

### 1. 发送短信验证码 & 登录（`compus.xiaofubao.com`）

- 需要确认：**接口路径**、**是否有动态 `sign` / `timestamp` / 设备指纹签名校验**、**密码/参数是否加密**、以及登录后返回的 `token` / `ymId`（或 `id`）。

```markdown
### 1.1 发送验证码（如有）
- Method & URL: POST https://compus.xiaofubao.com/...
- Required Headers:
  - Content-Type: ...
  - sign / timestamp / deviceId（如有）: ...
- Request Body:
  { ... }
- Response Body（成功/失败示例）:
  { ... }

### 1.2 登录提交
- Method & URL: POST https://compus.xiaofubao.com/...
- Required Headers:
  - ...
- Request Body:
  { ... }
- Response Body（标注 token、ymId/id 字段位置）:
  { ... }
```

### 2. App 会话换取 `application.xiaofubao.com` H5 凭证（`shiroJID`）

- 需要确认：从原生首页点击「生活用水」打开 `WebActivity` 时，App 是如何把 `token` / `ymId` 换算或透传成 `application.xiaofubao.com` 所需 `Cookie: shiroJID=...` 的（例如 302 跳转 URL 或鉴权换票接口）。

```markdown
- Method & URL: GET/POST https://.../
- Query / Body / Headers:
  - ...
- Response Headers / Body（Set-Cookie: shiroJID=... 或 redirect url）:
  - ...
```

### 3. 一卡通余额查询（`compus.xiaofubao.com`）

- 需要确认：点击「余额查询」时的实际接口路径、鉴权字段（`token` 还是 `shiroJID`）、余额数值单位（**元** 还是 **分**）与卡状态字段。

```markdown
- Method & URL: POST/GET https://compus.xiaofubao.com/...
- Required Headers:
  - ...
- Request Query / Body:
  { ... }
- Response Body（标注余额字段名与单位）:
  { ... }
```

### 4. 浴室 6 位动态用水码获取 / 刷新（`application.xiaofubao.com`）

- 需要确认：进入「生活用水 → 用水码」时请求的完整路由、是否延续 `?ymId=...&platform=YUNMA_APP` + `Cookie: shiroJID=...` 风格、返回的 6 位码字段名、有效倒计时/过期时间字段，以及会话失效时的错误码（对比电费接口 `statusCode == 204`）。

```markdown
### 4.1 查询账户 / 用水页面初始化信息（如有）
- Method & URL: POST/GET https://application.xiaofubao.com/...
- Headers / Query / Body:
  - ...
- Response Body（余额/钱包状态字段等）:
  { ... }

### 4.2 生成 / 刷新 6 位动态用水码
- Method & URL: POST/GET https://application.xiaofubao.com/...
- Required Headers:
  - Cookie: shiroJID=<REDACTED>
- Request Query / Body:
  { ... }
- Response Body（标注 6 位用水码、有效期/ttl、错误码字段）:
  { ... }
```

---

## 四、抓包与逆向时重点核查项

1. **签名校验（重点）**：如果 `compus.xiaofubao.com` 请求头包含随每次请求变动的 `sign`，需要明确参与拼接的字段顺序与摘要/加密算法（例如 MD5 / HMAC / AES / RSA）；若无动态签名仅靠 `token` / `shiroJID`，Kotlin 客户端可直接复用。
2. **微信 / 桌面端排查建议**：既然「生活用水」确认运行在 H5（`application.xiaofubao.com`）且易校园有微信小程序，若需免去 Android 15 系统证书限制，可在桌面端受信任环境抓微信小程序或使用已信任系统证书的测试设备定位上述 4 组路由。
3. **会期与刷新策略**：确认 `token` 与 `shiroJID` 各自有效期，以及过期后是否支持用保存的 `token` 无感重换 `shiroJID`（避免频繁让用户重新收短信验证码）。

---

## 五、当前工程内已预留的接入位点

拿到上述 4 组接口规格后，工程无需大规模改动，直接按以下文件对接即可：

- **新建客户端（建议路径）**：
  - `app/src/main/java/com/hmu/muye/data/campus/YixiaoyuanClient.kt`（或独立 `data/yixiaoyuan/` 包 + MockWebServer 单测）
- **状态模型**：
  - [CampusLifeModels.kt](../app/src/main/java/com/hmu/muye/data/campus/CampusLifeModels.kt#L24-L36) 中的 `YixiaoyuanState`（已含 `hasCredential`、`ymId`、`cardBalanceYuan`、`lowBalanceThresholdYuan`、`showerCode`、`showerCodeAt`、`isLowBalance`，可按需补充 `showerCodeExpiresAt`、`cardStatus`）
- **加密凭据持久化**：
  - [CampusLifeStore.kt](../app/src/main/java/com/hmu/muye/data/campus/CampusLifeStore.kt#L49-L99)（已支持 `Crypto` 加密存储 `shiroJID`，可再对称增加 `KEY_YXY_TOKEN_ENC`）
- **ViewModel 业务调度**：
  - [LifeViewModel.kt](../app/src/main/java/com/hmu/muye/ui/life/LifeViewModel.kt#L172-L205)（将 `saveYixiaoyuanConfig` / `refreshShowerCode` 替换为真实的登录、刷新一卡通余额与获取 6 位动态用水码逻辑）
- **UI 挂载入口**：
  - 生活页余额条、瓷砖与弹窗：[LifeScreen.kt](../app/src/main/java/com/hmu/muye/ui/life/LifeScreen.kt)（`ShowerCodeTile`、`YixiaoyuanConfigDialog`）
  - 我的 → 校园服务账户 → 易校园页：[ProfileDetailPages.kt](../app/src/main/java/com/fengkao/timetable/ui/profile/ProfileDetailPages.kt#L82-L152)（`YixiaoyuanAccountPage`）
