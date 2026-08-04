# WebCode Local（Android）

生产级 AI 编程助手**本地版**：手机端直连模型供应商（DeepSeek Responses API），内嵌 Ubuntu 26.04 LTS rootfs 作为完整 Linux 执行环境（proot 虚拟化），不依赖任何中间服务器。

## 功能

- **直连 DeepSeek Responses API**（`deepseek-v4-flash`）：流式输出、思考模式（`reasoning.effort`：none/low/medium/high）、服务端执行 `web_search`
- **内嵌 Ubuntu 26.04 LTS rootfs**：bash / apt / python 原生可用，AI 通过 `run_command` 在完整 Linux 内执行命令；proot 为源码编译（GPL-2.0），无硬编码路径
- **MCP 支持**：stdio（rootfs 内运行 npx/python MCP 服务器）与 HTTP 传输，工具自动合并
- **外部路径挂载**：可选挂载手机存储到 `/mnt/external`（默认内部存储 `/storage/emulated/0`，可多路径编辑/删除）
- 本地工具：`read_file` / `write_file` / `list_files` / `device_info` / `open_url` / `ask_user`，危险命令需用户审批
- 会话历史全部存储在本机；Markdown/LaTeX 渲染（Markwon）；代码块点击复制
- 后台运行：前台服务 + 忽略电池优化 + 1px 像素悬浮窗保活；小窗模式

## 目录结构

```
android-app/     Android 应用源码（Kotlin，Gradle 构建）
  app/src/main/java/com/webcode/app/
    local/       直连引擎：Responses API 客户端 + 工具循环 + MCP 客户端 + 本地会话存储
    termux/      proot/rootfs 运行时、保活/小窗服务、诊断日志
    ui/          聊天界面、设置页
local-agent/     参考用：Node.js 本地 Agent（独立实现，非本项目运行路径）
third_party/     内嵌开源组件：proot 源码（GPL-2.0）+ 各许可文本
THIRD_PARTY_NOTICES.md  开源合规清单
LICENSE          GPL-3.0
```

## 构建

```bash
cd android-app
# 需要 JDK 17+、Android SDK 34
./gradlew assembleDebug
```

## 使用

1. 安装 APK，打开进入设置页（标题显示 v3.0）
2. 填写 DeepSeek API Key（`https://api.deepseek.com`，模型 `deepseek-v4-flash`）
3. 点「下载 Ubuntu 26.04 LTS rootfs」（35MB，多源回退 + SHA 校验 + 进度条）
4. 进入对话，向 AI 下达任务（可配 MCP 服务器、挂载外部路径）

## 许可

- 本项目整体：**GPL-3.0**（详见 LICENSE）—— 内嵌 termux-app（GPL-3.0）移植代码
- **proot**（GPL-2.0，编译后二进制随 APK 分发）：源码见 `third_party/proot-src/`
- **libtalloc**（LGPL-3.0）、**libandroid-shmem**（BSD-2-Clause）：动态链接库，随 APK 分发
- **Markwon / OkHttp / AndroidX / Material**（Apache-2.0）：Gradle 依赖
- **Ubuntu base rootfs**（release 资产，非仓库内）：Ubuntu 各包自有许可

完整清单、修改说明与许可文本见 **THIRD_PARTY_NOTICES.md**。
