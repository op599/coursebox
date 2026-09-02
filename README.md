# 课程盒子 (Coursebox)

> 离线、轻量的音频课程播放与打包工具集。
> Offline, lightweight audio course player & packager.

作者主页 / Author: [wangxiuwen.com](https://wangxiuwen.com)

**课程盒子**是一套两件套工具，把"音频 + 文本"形态的学习课程（语言朗读、
古典文本、音乐视唱…）打成单个 zip 课程包，离线就能播放。

- Android 播放器是个"壳子"——只负责播，**不内置任何课程内容**。
- 已安装用户可展示二维码，让附近的人通过同一 Wi-Fi / 手机热点下载 APK；也可调用蓝牙或系统互传分享。
- 桌面打包工具（Mac / Windows / Linux 都跑）把你自己的素材打成可分发的
  `.coursebox.zip`。
- 课程包是内容寻址的 sha256 资源仓 + 一个 `manifest.json`，离线、可校验、
  可去重，拿 U 盘 / AirDrop / 局域网 / 网盘传都行。

**这个仓库不附带任何课程数据**，你拿到的就是工具本身。课程素材自己准备，
版权自己负责。

## 仓库结构

```
.
├── android/      Kotlin + Jetpack Compose Android 播放器（APK）
├── packager/     Compose Multiplatform Desktop GUI 打包工具
│                 (macOS / Windows / Linux)
├── spec/         课程包格式规范 + JSON Schema
└── .github/      Release 工作流
```

## 课程包格式 (`*.coursebox.zip`)

```
manifest.json                  # 顶层元数据 + 资源清单
objects/<sha256>.<ext>         # 内容寻址的资源仓
                               #   - 课次详情 JSON
                               #   - 音频 (.mp3 / .m4a / .wav / .opus)
                               #   - PDF、图片等
```

每个资源在 `manifest.json` 里按 sha256 索引：

- **天然去重**：两节课用同一段音频，存一份就够。
- **重复导入幂等**：再次导入相同包只搬新资源。
- **可校验**：每个文件按声明的 hash 校验。

完整 schema 见 [`spec/format.md`](./spec/format.md)。

## 快速开始

### 装播放器（Android）

1. 在 [Releases](../../releases) 下载最新的 `coursebox-android-<version>.apk`。
2. 装到 Android 7+ 的手机（arm64）。
3. 进入资源库 tab，右上角点 **+** → "从本地 zip 导入"，选一个 `.coursebox.zip`。

### 打一个课程包（桌面）

1. 在 [Releases](../../releases) 下载对应平台：
   - `coursebox-packager-macos-<arch>.dmg`
   - `coursebox-packager-windows-x64.msi`
   - `coursebox-packager-linux-x64.deb`
2. 打开打包工具。
3. 指定一个包含你音频 + `lessons.json` 的文件夹。
4. 点 **打包成 .coursebox.zip**。

打出来的 zip 就是 Android 播放器导入的格式。没有服务器，没有云，
U 盘 / AirDrop / WeTransfer / NAS 自己挑。

## 课程类型 (`type`)

播放器认识几个 type 标识，按类型给不同的渲染 UI；不认识的统一回退到
`audio_course` 通用模式：

| `type`            | 渲染 |
|-------------------|------|
| `audio_course`    | 通用播放列表 + 字幕（默认兜底） |
| `nce`             | 书 / 课次树状结构 + 中英双语正文 + 单词表 |
| `chinese_poetry`  | 经典文献浏览器，按来源筛选、全文检索 |
| `music`           | 按年级 / 专题分组的音频 + PDF |

作者可以自定义新的 `type` 值——播放器认不出来就退回 `audio_course`。

## 从源码构建

### Android 播放器

```bash
cd android
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### 桌面打包工具

```bash
cd packager
./gradlew :app:run                                # 开发模式
./gradlew :app:packageDistributionForCurrentOS    # 打安装包
```

## 设计动机

绝大多数"学习类 app" 把内容跟代码一块塞进二进制——APK 几百 MB、绑死
一套教材、想换内容就只能等新版本。课程盒子反过来：

- 播放器小（~19 MB APK）、通用。
- 内容单独走 `.coursebox.zip`，自己出版、自己版本管理、自己分发。
- 任何 "音频 + 文本" 的素材都能装进来：外语朗读、古文吟诵、乐理视唱…

## 许可

Apache 2.0，见 [`LICENSE`](./LICENSE)。

**关于课程内容的版权**：本仓库不附带任何课程素材。你装进 `.coursebox.zip`
里的音频、文本、图片归你自己负责——确认你有权再分发。
