<h1 align="center">牧野生活（HMU）</h1>

<p align="center"><strong>河南牧业经济学院（龙子湖校区）纯 Kotlin 校园生活聚合客户端</strong></p>

`牧野生活（HMU）` 基于 MIT 开源项目 [`f3ngka0/youwu`](https://github.com/f3ngka0/youwu) 二次开发并适配河南牧业经济学院（学校代码 `10469`）。全程采用 **Kotlin + Jetpack Compose + Material 3 + 液态玻璃（Liquid Glass）** 实现，无任何自建后端，账号密码、Cookie 与凭据均使用 Android Keystore + AES-GCM 加密保存在本机。

---

## 核心功能

- **自动更新的课程表**：优先通过超星学习通官方接口（`kb.chaoxing.com`）与学校教务系统（`hnuahe.jw.chaoxing.com`）双通道同步课表，支持画布网格手动排课、课程笔记与定时提醒。
- **学习通通知中心**：自动汇总作业、测验、签到与调课异动公告，支持关键字检索、本地已读/归档与 WorkManager 后台提醒。
- **桌面小组件**：提供 `2×2`「下一节课」与 `4×2`「下一节课 · 今日概览」，按上课、课间、无课状态动态切换。
- **校园生活服务**：
  - **慧生活 798 饮水**：手机号 + 验证码登录、扫码/编号绑定设备、一键直连启动饮水机。
  - **易校园一卡通与浴室用水码**：短信/凭据登录、`HMAC-SHA256` 签名直连一卡通余额查询（支持随时关闭余额展示），以及 8 位动态浴室用水码查询与刷新。
  - **U净洗衣**：扫码/检索校内洗衣机、实时余位与自洁/桶状态、一键跳转启动。
  - **校园快递聚合**：本地智能解析取件码短信，按驿站与取件码一键复制归档。
- **应用内更新（OTA）**：直连 GitHub Releases / 可选静态 `update.json`（支持加速镜像前缀配置），应用内流式下载、校验 `SHA-256` 后拉起系统安装器完成升级。

> 注：本客户端面向河南牧业经济学院龙子湖校区定制，**不包含任何宿舍电费查询与充值界面**。

---

## 环境与从源码构建

- **JDK**：17 或 21（`JAVA_HOME`）
- **Android SDK**：Platform 36、Build-Tools 36.0.0
- **Gradle**：使用仓库内置 Wrapper（8.14.3）

```bash
# 运行全部本地单元测试
./gradlew :app:testDebugUnitTest

# 构建 Debug APK
./gradlew :app:assembleDebug

# 构建 Release APK（未配置 keystore.properties 时产出未签名包）
./gradlew :app:assembleRelease
```

### 可选本地配置

- `water.properties`（复制 `water.properties.example`）：自定义饮水服务 `API_GATEWAY` / `API_CID`。
- `keystore.properties`（复制 `keystore.properties.example`）：配置本地签名密钥路径与凭据，完整发版与签名指引见 [docs/RELEASE_AND_UPDATE_GUIDE.md](docs/RELEASE_AND_UPDATE_GUIDE.md)。

---

## 相关文档

- [架构说明](docs/ARCHITECTURE.md)
- [GitHub 自动发布与应用内更新配置指南](docs/RELEASE_AND_UPDATE_GUIDE.md)
- [易校园接口实测规格](docs/YIXIAOYUAN_API_SPEC_VERIFIED.md)

---

## 开源协议与致谢

本项目采用 [MIT License](LICENSE) 开源。

- 基础工程框架、课表画布、液态玻璃组件、学习通通知中心、慧生活 798 与小组件架构来自 MIT 开源项目 [`f3ngka0/youwu`](https://github.com/f3ngka0/youwu)。
- 其他第三方组件与开源许可详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，也可在应用内 **「我的 → 关于 → 开源许可」** 查看。
