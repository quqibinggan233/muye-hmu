# GitHub 自动发布与应用内更新配置指南（HMU 牧野生活）

本项目已内置**应用内流式下载 + SHA-256 校验 + 系统安装器拉起**能力，以及配套的 [release.yml](../.github/workflows/release.yml) GitHub Actions 自动发版流水线。建好自己的 GitHub 仓库后，按以下步骤完成一次配置即可使用。

---

## 一、生成固定签名证书（重要：仅首次执行一次）

> **覆盖安装前提**：Android 要求同一包名覆盖升级时必须使用**同一把签名证书**，且新版 `versionCode` 严格大于旧版。请妥善备份 `.jks` 文件与密码，**切勿提交到 Git 仓库**（已加入 `.gitignore`）。

在项目根目录执行：

```bash
keytool -genkeypair -v \
  -keystore timetable-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias muye
```

按提示输入密钥库密码、密钥密码及开发者信息后，在根目录复制一份 `keystore.properties`（供本地偶尔打 Release 包用，同样已被 `.gitignore` 忽略）：

```bash
cp keystore.properties.example keystore.properties
```

修改 `keystore.properties` 内容为：

```properties
storeFile=timetable-release.jks
storePassword=你的密钥库密码
keyAlias=muye
keyPassword=你的密钥密码
```

转成 Base64（复制输出内容，第 3 步填到 GitHub Secrets）：

```bash
base64 -i timetable-release.jks | pbcopy   # macOS 已自动复制到剪贴板
```

---

## 二、创建 GitHub 仓库并配置更新源

1. 在 GitHub 新建一个仓库（例如 `你的用户名/muye-hmu`）。
2. 编辑项目根目录 [gradle.properties](../gradle.properties)，填入你自己的仓库：

```properties
muye.updateRepo=你的用户名/muye-hmu
# 可选：若你把 release.yml 产出的 update.json 镜像到了 CDN / Cloudflare Pages，可在此填入：
muye.updateManifestUrl=
```

> 注：就算暂时没重编译打包，也可以直接在手机 App 内进入 **「我的 → 关于 → 更新源与加速设置」** 动态填写 `owner/repo` 与可选的 **GitHub 下载加速前缀**（如 `https://gh-proxy.com/`）。

3. 在项目根目录推送本地代码到 GitHub：

```bash
git init
git status                  # 确认不包含 timetable-release.jks、keystore.properties、water.properties
git add .
git commit -m "init: muye-hmu"
git branch -M main
git remote add origin https://github.com/你的用户名/muye-hmu.git
git push -u origin main
```

---

## 三、配置 GitHub Actions 签名 Secrets

打开 GitHub 仓库页面的 **Settings → Secrets and variables → Actions → New repository secret**，添加以下 4 个 Secret：

| Secret 名称 | 填入值 |
| :--- | :--- |
| `KEYSTORE_BASE64` | 第 1 步 `base64` 复制出来的字符串 |
| `KEYSTORE_PASSWORD` | `.jks` 的密钥库密码 |
| `KEY_ALIAS` | 上面示例为 `muye` |
| `KEY_PASSWORD` | 密钥密码 |

---

## 四、发版流程（以后每次更新只需 3 步）

1. 在 [app/build.gradle.kts](../app/build.gradle.kts#L34-L35) 中递增 `versionCode` 并修改 `versionName`（例如 `versionCode = 2`、`versionName = "0.2.0"`）。
2. 提交并打一个以 `v` 开头的标签推送：

```bash
git add app/build.gradle.kts
git commit -m "release: v0.2.0"
git push
git tag v0.2.0
git push origin v0.2.0
```

3. 等待 GitHub Actions 跑完：
   - Actions 会自动运行单元测试、编译签名 Release APK、计算 `SHA-256`，并发布到 GitHub Releases（附带 `.apk`、`.sha256` 与 `update.json`）。
   - 手机上打开 **牧野生活**（首次建议先用本签名编出的 Release APK 安装一次替换旧 Debug 包），之后进入 **「我的 → 关于 → 检查更新」** 或等待冷启动检查，即可看到更新日志并**在应用内直接下载进度、校验并覆盖安装**。
