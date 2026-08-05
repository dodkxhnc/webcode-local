# WebCode Local（Android）

> **Beta 公测版** · 手机端直连模型供应商的生产级 AI 编程助手

WebCode Local 是运行在 Android 手机上的 AI 编程助手：**不依赖任何中间服务器**，App 直连模型供应商（DeepSeek Responses API），内嵌 **Ubuntu 26.04 LTS rootfs** 作为完整 Linux 执行环境（proot 用户态虚拟化），AI 可以在手机上执行真实 Linux 命令、读写工作区文件、甚至操作内置终端（tty）。

当前状态：**Beta 公测**。核心链路已稳定，欢迎反馈问题（详见[已知问题](#已知问题beta)）。

## ✨ 功能特性

### 对话与模型
- 直连 DeepSeek Responses API（`deepseek-v4-flash`），无中间服务器、无订阅
- 流式输出、思考模式（`reasoning.effort`：none / low / medium / high）、联网搜索（服务端执行）
- 多轮工具循环：可连续调用多个工具直到任务完成，工具出错会自动分析并继续修复
- 输出与轮次不做人为限制（不设 `max_output_tokens`，轮次无上限）

### 完整 Linux 执行环境
- 内嵌 **Ubuntu 26.04 LTS rootfs**（arm64，约 35MB，多源下载 + SHA 校验 + 断点续传）
- `bash` / `apt` / `python3` 原生可用；AI 通过 `run_command` 在完整 Linux 中执行命令
- 国内镜像加速（阿里云 `ubuntu-ports`，按架构自动选择）、DNS 自动配置、dpkg 异常自动修复
- 内置终端（tty）：Termux 风格终端仿真器，多会话、长按复制、双指缩放、Ctrl/Alt 组合键
- **AI 终端协作**（设置中可选开启）：授权后 AI 可读取当前终端输出、向终端注入命令并执行

### 本地工具
- `read_file` / `write_file` / `list_files` / `device_info` / `open_url` / `ask_user`
- MCP 支持：stdio（rootfs 内运行 npx / python MCP 服务器）与 HTTP 传输
- 危险命令需用户审批（`rm -rf`、`mkfs`、`sudo` 等自动拦截）

### 界面与体验
- Markdown / LaTeX / 表格完整渲染；长按消息弹出操作菜单（复制选中 / 复制全文 / 复制代码块 / 复制原文）
- 会话历史全部存储在本机，退出自动恢复上次会话
- 小窗模式：悬浮球常驻，多任务时快速对话；后台前台服务 + 忽略电池优化保活
- 设置页：API Key / 模型 / 推理强度 / rootfs 管理 / 外部路径挂载 / MCP 服务器管理 / tty 权限开关

## 📦 快速开始

1. 从 [Releases](https://github.com/dodkxhnc/webcode-local/releases) 下载最新 APK 并安装
2. 打开 App，进入设置页填写：
   - **API Key**：DeepSeek API Key
   - **接口地址**：`https://api.deepseek.com`
   - **模型**：`deepseek-v4-flash`
3. 下载 **Ubuntu 26.04 LTS rootfs**（约 35MB，自动完成）
4. 回到对话页，向 AI 下达任务即可

> 提示：首次使用建议在设置中开启「使用 Ubuntu rootfs 执行命令」，AI 才具备完整 Linux 能力。

## 🖥️ 内置终端

- 顶部会话条：`＋` 新建会话，长按会话名删除
- 底部扩展键：ESC / TAB / CTRL / ALT / 方向键等（CTRL/ALT 单次生效，输入一个字符后自动复位）
- 双指缩放调整字号；长按终端文本可复制（复制选中 / 复制全文 / 复制代码块 / 复制原文）
- ⌨ 按钮弹出/收起底部命令输入框；点击终端区域直接用 tty 键盘输入

## 🔒 隐私与数据

- **所有对话与文件操作均在本机完成**；仅将消息内容发送给你配置的模型供应商（DeepSeek）
- 会话历史、设置、rootfs 均存储在应用私有目录，不会上传
- 终端 tty 协作功能**默认关闭**，需在设置中显式开启授权
- 危险命令执行前需用户逐条审批

## 已知问题（Beta）

- 部分安卓输入法在终端页可能存在兼容性差异（已禁用安全键盘模式）
- rootfs 首次下载依赖网络环境，多源自动回退
- 小窗模式与全屏模式会话共享存储，切换时如有显示延迟请下拉刷新

## 🛠️ 构建

```bash
cd android-app
# 需要 JDK 17+、Android SDK 34（NDK 26.1）
./gradlew assembleDebug
```

## 🏗️ 技术架构

```
Android App (Kotlin)
├── 直连引擎（LocalEngine）
│   ├── Responses API 客户端（流式 SSE）
│   ├── 多轮工具循环（function_call 回传）
│   └── MCP 客户端（stdio / HTTP）
├── 终端运行时（TermuxRuntime）
│   ├── Ubuntu 26.04 rootfs（proot -0 虚拟化）
│   ├── PTY 终端仿真（Termux TerminalEmulator/View）
│   └── dpkg / apt / DNS / 镜像 自动维护
└── UI
    ├── 对话页（Markwon：Markdown + LaTeX + 表格）
    ├── 设置页 / 小窗（悬浮球）/ 终端页
```

## 🙏 特别鸣谢

本项目站在巨人的肩膀上，特别感谢以下开源项目与团队：

- **[Termux](https://github.com/termux/termux-app)**（GPL-3.0）—— 终端仿真器（TerminalEmulator / TerminalView）与 Android 终端生态的奠基者
- **[proot](https://github.com/proot-me/proot)**（GPL-2.0）—— 用户态 Linux 虚拟化，让完整 Ubuntu 在手机上运行成为可能
- **[Markwon](https://github.com/noties/Markwon)**（Apache-2.0）—— 高性能 Markdown / LaTeX 渲染引擎
- **[OkHttp](https://github.com/square/okhttp)**（Apache-2.0）—— 网络层
- **[AndroidX / Material Components](https://developer.android.com/)**（Apache-2.0）—— 界面基础组件
- **[Ubuntu](https://ubuntu.com/)** —— rootfs 与软件生态
- **[DeepSeek](https://deepseek.com/)** —— 提供模型 API 服务
- **所有参与内测与公测的用户** —— 你们的反馈让这个项目一步步走向成熟

完整开源合规清单与修改说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 📄 许可

- 本项目整体：**GPL-3.0**（见 [LICENSE](LICENSE)）
- 内嵌组件：proot（GPL-2.0）、libtalloc（LGPL-3.0）、libandroid-shmem（BSD-2-Clause）、Markwon/OkHttp/AndroidX（Apache-2.0）
- Ubuntu rootfs 以 release 资产形式分发（Ubuntu 各包自有许可）
