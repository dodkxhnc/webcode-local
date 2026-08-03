# WebCode Local（Android）

生产级 AI 编程助手 **本地版**：手机端直连模型供应商（DeepSeek Responses API），内嵌 Termux 运行时执行 shell 命令，不依赖任何中间服务器。

## 功能

- **直连 DeepSeek Responses API**（`deepseek-v4-flash`）：流式输出、思考模式（`reasoning.effort`：none/low/medium/high）、服务端执行 `web_search`
- **内嵌 Termux Linux 运行时**（移植自 termux-app）：bash / coreutils / apt，AI 可通过 `run_command` 在手机本地执行 sh，危险命令需用户审批
- 本地工具：`read_file` / `write_file` / `list_files` / `device_info` / `open_url` / `ask_user`
- 会话历史全部存储在本机（`filesDir/local_sessions/`）
- 后台运行：前台服务 + 忽略电池优化 + 1px 像素悬浮窗保活
- 小窗模式：悬浮气泡 → 可拖动小窗聊天

## 目录结构

```
android-app/     Android 应用源码（Kotlin，Gradle 构建）
  app/src/main/java/com/webcode/app/
    local/        直连引擎：Responses API 客户端 + 工具循环 + 本地会话存储
    termux/       Termux 运行时（GPL-3.0，移植自 termux-app）+ 保活/小窗服务
    ui/           聊天界面、设置页
local-agent/     参考用：Node.js 本地 Agent（独立实现，非本项目运行路径）
LICENSE          GPL-3.0
```

## 构建

```bash
cd android-app
# 需要 JDK 17+、Android SDK 34
./gradlew assembleDebug
```

## 内嵌 Termux 的出处（GPL-3.0 声明）

本项目 `android-app/app/src/main/java/com/webcode/app/termux/TermuxRuntime.kt` 为
[termux-app](https://github.com/termux/termux-app)（GPL-3.0，作者 Leonid Plyushch 及贡献者）
中 `TermuxInstaller.java` / `TermuxShellEnvironment.java` / `TermuxConstants.java` 的移植改写：

- bootstrap 安装流程（下载 → 解压 → SYMLINKS.txt 软链 → 权限 → usr-staging→usr）同源
- 环境变量（PREFIX/HOME/LD_LIBRARY_PATH/TMPDIR/LANG…）同源
- bootstrap 包及校验和与 termux-app 官方构建一致

按 GPL-3.0 要求，本仓库以 GPL-3.0 授权提供完整源代码；APK 分发亦遵循 GPL-3.0。

## 使用

1. 安装 APK，打开进入设置页
2. 填写 DeepSeek API Key（`https://api.deepseek.com`，模型 `deepseek-v4-flash`）
3. 点击「安装/更新 Linux 运行时」下载 bootstrap（官方 GitHub + 公共加速代理多源回退，SHA-256 校验）
4. 进入对话，向 AI 下达任务即可

## 许可

GPL-3.0（详见 LICENSE）。上游项目 termux-app 亦为 GPL-3.0。
