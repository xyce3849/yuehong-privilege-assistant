# 月虹提权助手

月虹提权助手是一个内置 Stellar Manager、Server 与 API 的 Android 提权工作台。应用不再依赖外部 Shizuku 或外部 Stellar 管理器，可直接在应用内通过 Android 无线调试完成 Stellar 自激活，再使用 `Stellar.newProcess()` 执行命令。

- 应用 ID：`roro.stellar.yuehong`
- 版本：`v1.2`（`versionCode 120`）
- 最低系统：Android 9（API 28）
- 目标系统：Android API 37
- ABI：`arm64-v8a`

## 运行流程

1. 启动时验证服务端签名公告、客户端版本与频道授权。
2. 检查内置 Stellar Binder 和 `stellar` 权限。
3. 服务未启动时进入应用内无线调试自激活页；配对、连接和服务启动均由内置 Stellar 组件完成。
4. 服务已启动但权限未确认时，使用 Stellar 自身授权页完成显式授权。
5. 授权后进入命令工作台，终端、在线提权和本地提权文件都通过 `Stellar.newProcess()` 执行。
6. 在线提权继续使用 DMKPZ 精确设备匹配、签名响应和多线路资源回退；本地文件模式完全不请求服务器。
7. 提权成功后可按原流程检测并激活 KernelSU；KernelSU 接管后的 `su` 不可用时立即停止后续命令。

应用保留正式版包名；使用相同证书签名时可覆盖安装现有正式版。公开源码不附带发行证书。整合工程未使用 Stellar 上游的 `sharedUserId`。

## 源码结构

```text
assistant/  月虹提权助手 UI、启动验证、设备匹配、多线路下载和提权业务
manager/    Android 宿主、自激活界面、无线调试、授权与 Binder 接收组件
server/     以 ADB shell 或 Root 身份运行的 Stellar 特权服务
api/        随工程固定版本交付的 Stellar API、AIDL、Provider 与共享代码
shizuku/    Stellar 内部保留的 Shizuku 兼容层
LICENSES/   第三方许可证文本
```

`assistant` 是 Android Library，最终由 `manager` 打包成唯一 APK。桌面唯一入口仍是月虹提权助手的 `roro.stellar.yuehong.activities.MainActivity`；Stellar 原管理器首页、更新器入口和安装未知 APK 权限不进入发行 Manifest。

## 服务器配置

复制 `server.properties.example` 为根目录下的 `server.properties`，填写：

```properties
compatibilityEndpoint=
moduleId=
protocolV2Endpoint=
channelEndpoint=
protocolV2PublicKey=
protocolV2KeyId=
romProtocolV2Endpoint=
romProtocolV2PublicKey=
romProtocolV2KeyId=
channelJoinUrl=
updateUrl=
```

`moduleId` 应填写为构建者自己服务端配置的模块 ID；示例文件不携带月虹正式服务的模块 ID 或其他运营配置。

公开源码不包含运营域名、接口路径、模块标识、公钥、key ID、频道链接、更新地址、签名口令或密钥库。需要联网功能时，由构建者在本地复制示例文件后填写自己的 HTTPS 服务和协议密钥。`server.properties`、签名属性和密钥库不会进入公开源码包。

## 构建

需要：

- JDK 21
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0
- Android NDK `29.0.13113456`
- CMake 3.22.1 或更高版本

Windows 中文路径下请使用工程自带脚本。脚本会创建一个仅在构建期间存在的 ASCII 临时目录联接，避免 CMake/Ninja 的中文路径限制，不会复制源码：

构建调试版：

```powershell
.\tools\build_android.ps1 -Variant Debug
```

构建正式版：

```powershell
.\tools\build_android.ps1 -Variant Release
```

若工程位于纯 ASCII 路径，也可直接执行 `./gradlew :manager:assembleRelease`。

Release 签名读取根目录下由构建者自行创建的 `keystore.properties`；公开源码不附带任何发行密钥或签名属性。主要产物会同步到：

```text
out/apk/月虹提权助手-v1.2-release.apk
out/mapping/mapping-v1.2.txt
```

## 安全边界

- 自激活只负责启动 ADB shell/Root 身份的 Stellar 服务，不会让 Android 应用进程本身变成 Root。
- 助手在每次执行前同时检查 Binder 存活状态和 Stellar 授权状态。
- 在线业务响应、资源元数据和后续线路票据继续执行 Ed25519 与 SHA-256 校验。
- 本地文件先复制到应用私有缓存，再由 Stellar 写入 `/data/local/tmp/preload.so`；不会修改用户选择的原文件。
- 正式运营接口仍校验应用 ID 和正式签名证书；自行重签名的构建不会连接正式服务。

## 许可与归属

本工程是一个由不同许可证文件组成的组合项目。组合发行版以 MPL-2.0 为顶层许可证，原有文件继续保留各自许可证：

- 月虹提权助手集成与修改部分遵循 MPL-2.0。
- Stellar 与月虹修改文件遵循 MPL-2.0，完整文本位于 [LICENSES/MPL-2.0.txt](LICENSES/MPL-2.0.txt)。
- Stellar 中源自 Shizuku 的文件保留 Apache-2.0。
- GhostLock 源码保留 Apache-2.0。

根目录 [LICENSE](LICENSE) 为 MPL-2.0 完整文本。完整归属与修改说明见 [NOTICE.md](NOTICE.md)，第三方许可文本见 [LICENSES](LICENSES)。发布前可运行 `tools/verify_public_source.ps1` 检查配置、密钥文件和构建产物是否被误加入。
## GhostLock 独立页面

启动验证与频道授权通过后，应用先进入提权模式选择页：

- **内核 6.6 / 6.12 · GhostLock**：无需 Stellar 激活，按精确 `uname -r` 使用内置或导入 offsets；支持 `boot.img`、骁龙 `boot.img + xbl_config.img`、天玑 `boot.img` 与完整 OTA 链接。
- **通用提权助手 · Stellar**：继续保留机型匹配、服务端多线路提权文件、完全本地文件、自激活和命令控制台。

GhostLock 完整上游源码固定在 `third_party/ghostlock`。更新其原生组件后执行：

```powershell
.\tools\build_ghostlock.ps1 -NdkVersion 29.0.13113456 -Offline
python .\tools\generate_ghostlock_kernels.py
```

生成的 arm64 文件固定写入 `assistant/src/main/jniLibs/arm64-v8a/libghostlock.so` 与 `libextract.so`；Android 工程仍由 `tools/build_android.ps1` 构建。
