# Qwen3 中文语音（Android）

基于 Qwen3-TTS 与 `qwen3-tts.cpp` 的安卓本地语音合成应用。

本项目在手机本机运行语音模型，生成的音频保存在应用私有目录，并支持录音创建自定义音色。

## 功能

- 手机本地运行 Qwen3-TTS，无需调用云端语音接口
- 全中文界面，语音语言固定为中文
- 使用 Q4_K_M 量化模型，支持模型下载与加载
- 录制参考语音并提取说话人特征，创建自定义音色
- 保存合成历史，支持播放、停止、删除与 WAV 导出
- 支持 CPU 推理与线程数调整
- 显示模型加载、下载进度、合成耗时及预计剩余时间

## 模型文件

首次使用时，在「设置」页面下载模型文件：

- `qwen-tokenizer-12hz-Q4_K_M.gguf`
- `qwen-talker-0.6b-base-Q4_K_M.gguf`

模型文件来自 [Serveurperso/Qwen3-TTS-GGUF](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF)，下载后保存在应用私有目录中。

## 设备要求

- Android 12（API 31）或更高版本
- ARM64 设备（`arm64-v8a`）
- 建议预留足够存储空间用于模型文件和生成音频

## 构建

初始化原生推理引擎子模块：

```bash
git submodule update --init --recursive
```

构建 Debug APK：

```bash
./gradlew :app:assembleDebug
```

构建 Release APK：

```bash
./gradlew :app:assembleRelease
```

APK 输出目录：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

GitHub Actions 会在推送到 `main` 分支时自动构建 APK，并上传构建产物；推送 `v*` 标签时还会创建 GitHub Release。

## 使用

1. 安装 APK 并打开应用。
2. 进入「设置」，下载 Q4_K_M 模型。
3. 点击加载模型。
4. 进入「音色」，录制参考语音并创建自定义音色。
5. 返回「工作台」，输入中文文本并选择音色，开始合成。
6. 在「历史」页面播放、导出或删除生成记录。

## 项目结构

```text
app/                         Android 应用、Compose 界面与 JNI 封装
app/src/main/cpp/             Android CMake 原生构建配置
app/src/main/java/.../data    录音与 Room 数据库
external/qwen3-tts.cpp        Qwen3-TTS 原生推理引擎子模块
```

## 许可

本项目遵循 MIT License，详见 [LICENSE](LICENSE)。
