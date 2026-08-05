# 第三方开源组件清单（THIRD PARTY NOTICES）

本应用（WebCode Local）使用了以下开源软件。按各许可证要求，在此完整声明。

## 项目自身许可

- 本仓库整体：**GPL-3.0**（见 LICENSE）—— 因为内嵌了 GPL-3.0 的 termux-app 移植代码，整个应用以 GPL-3.0 发布。

## 内嵌/打包组件

| 组件 | 版本/来源 | 许可证 | 用途 | 源码位置 |
|---|---|---|---|---|
| termux-app | 官方 master（2026 年获取） | GPL-3.0 | TermuxRuntime.kt 的移植来源（bootstrap 安装/环境变量逻辑） | https://github.com/termux/termux-app |
| proot | termux/proot v5.1.107.89 | **GPL-2.0** | 应用内嵌执行环境（编译后二进制随 APK 分发，源码在本仓库 third_party/proot-src/） | https://github.com/termux/proot |
| libtalloc | 2.4.3（termux 包） | **LGPL-3.0** | proot 的依赖库（动态链接，随 APK 分发 .so） | https://gitlab.com/samba-team/samba（talloc 组件） |
| libandroid-shmem | 0.7（termux 包） | BSD-2-Clause（见 licenses/libandroid-shmem-LICENSE） | proot 的依赖库（动态链接） | https://github.com/termux/libandroid-shmem |
| Ubuntu base rootfs | 26.04 LTS（cdimage.ubuntu.com） | Ubuntu 各包自有许可（GPL/BSD/MIT 等） | AI 的执行系统（release 资产，非仓库内） | https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/ |
| termux bootstrap | 2026.02.12-r1 | 各包自有许可（bash/coreutils=GPL-3.0，apt=GPL-2.0 等） | 已弃用（不再分发） | https://github.com/termux/termux-packages |

## Gradle 依赖（随 APK 分发）

| 依赖 | 许可证 | 说明 |
|---|---|---|
| androidx.appcompat / core-ktx / recyclerview / constraintlayout | Apache-2.0 | Android 官方库 |
| com.google.android.material:material | Apache-2.0 | Material 组件 |
| com.squareup.okhttp3:okhttp / okhttp-sse (4.12.0) | Apache-2.0 | HTTP/SSE 客户端 |
| io.noties.markwon:core / ext-latex / inline-parser (4.6.2) | Apache-2.0 | Markdown/LaTeX 渲染 |
| com.atlassian.commonmark:commonmark-ext-gfm-tables (0.13.0) | BSD-2-Clause | Markdown 表格解析（ext-tables 本地化后的解析依赖） |

## 修改说明

- `third_party/proot-src/` 为 termux/proot v5.1.107.89 源码副本（GPL-2.0），含我们的两处编译修复：
  1. `extension/ashmem_memfd/ashmem_memfd.c`：补 `#include <string.h>`
  2. `extension/sysvipc/sysvipc_shm.c`：补 sys/shm.h / sys/ipc.h / shm.h 头
- 编译方式：Android NDK 26，`PROOT_WITH_LIBANDROID_SHMEM=true`，
  `CC=<arch>-linux-android24-clang`，`CPPFLAGS="-DARG_MAX=131072 -DVERSION=\"5.1.107.89\""`。
  最终二进制无 RUNPATH（依赖 LD_LIBRARY_PATH 加载 libtalloc/libandroid-shmem），
  loader 为源码编译的静态二进制（内嵌于 proot 亦可用）。
- `app/src/main/java/com/webcode/app/termux/TermuxRuntime.kt` 为 termux-app 的
  TermuxInstaller / TermuxShellEnvironment / TermuxConstants 的 Kotlin 移植改写
  （GPL-3.0），文件头已注明出处。
- `app/src/main/java/com/termux/` 为 termux-app 的 terminal-emulator 与 terminal-view
  源码目录拷贝（GPL-3.0），含以下修改：
  1. `TerminalSession.java`：`cleanupResources()` 的 `JNI.close(fd)` 改为
     本项目 `TerminalPty.ptyClose()`（不打包 termux 原生库，避免 UnsatisfiedLinkError）
  2. `TerminalViewClient.java` / `TerminalView.java`：新增默认回调
     `onModifierKeysConsumed()`，实现 CTRL/ALT 单次生效（输入一个字符后自动复位）
- `app/src/main/java/io/noties/markwon/ext/tables/` 为 markwon-ext-tables 4.6.2
  源码本地化拷贝（Apache-2.0），含修改：`TableRowSpan.java` 修复表格行
  垂直对齐（baseline 居中 + 内容垂直居中），解决表格文字相对正文的垂直偏移。

## 许可文本

- GPL-3.0：见仓库根 LICENSE
- GPL-2.0：third_party/licenses/GPL-2.0.txt
- LGPL-3.0：third_party/licenses/LGPL-3.0.txt
- Apache-2.0：third_party/licenses/Apache-2.0.txt
- BSD-2-Clause（libandroid-shmem）：third_party/licenses/libandroid-shmem-LICENSE

如有任何遗漏或版权问题，请提 issue 告知。
