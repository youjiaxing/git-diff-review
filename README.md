# GoLand Diff Navigation

在 GoLand 的 Git 暂存区 Diff 中跟随代码跳转，继续在同一个「提交」预览标签页审阅目标文件。

## 行为

- 保留原生声明解析及多目标选择；目标有未暂存修改时进入 INDEX ↔ 本地 Diff，只有已暂存修改时进入 HEAD ↔ INDEX Diff。
- 复用当前提交预览、同步提交树选中项，保留 IDE 原生的暂存、取消暂存、还原控件。
- 恢复目标光标，并通过现有 Back / Forward 键位在本次 Diff 审阅路径中移动。
- 普通编辑器、无修改文件、冲突视图、分屏跳转及不支持的视图交回 IDE。
- 源码标签不会被自动关闭；插件本身不执行 Git 写入。
- Diff 右键菜单的 **Navigate Within Staging Diff** 可在当前项目临时关闭功能。

## 兼容范围

首版适配 **GoLand 2026.2，build 262**，开发 SDK 为 **2026.2.2.1 / GO-262.10315.160**，需要 JDK 25 构建。

目前接管提交工具窗口的**双栏 Git Staging Diff**。Unified、Combined、三栏、历史提交及普通单文件比较使用原生导航。内部 API 有版本约束，因此插件将兼容版本限定在 `262.*`。

INDEX 文件与工作区内容相同时，声明解析会使用工作区 PSI 上下文，以支持从仅已暂存文件继续跳转。内容不同时保留原生解析，不把 INDEX 偏移直接套到工作区。历史记录保留侧别和内容指纹；内容已变化时不会使用旧偏移定位。

## 本地构建

无需网络下载构建依赖，直接使用已安装的 GoLand SDK：

```powershell
.\scripts\build-local.ps1 `
  -IdePath 'C:\path\to\GoLand' `
  -JdkPath 'C:\path\to\jdk-25'
```

输出 `build/local/goland-diff-navigation-0.1.0.zip`。脚本会先编译并运行历史回归测试。

标准 Gradle 构建使用 [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)：

```powershell
# JAVA_HOME 指向 JDK 25
.\gradlew.bat buildPlugin -PlocalIdePath='C:\path\to\GoLand'
# 省略 localIdePath 时下载 gradle.properties 指定的 SDK
```

Gradle 输出在 `build/distributions/`。首次运行需要访问 Gradle、JetBrains 和 Maven 仓库；本地 SDK 脚本可用于这些服务不可访问时。

## 验证

`src/test` 包含无需第三方依赖的历史回归检查，覆盖连续跳转、停留位置、后退后分叉、失败不移动游标以及容量上限。

`src/smoke` 是单独打包的 IDE 集成测试插件，**不包含在正式安装包内**：

```powershell
.\scripts\start-sandbox.ps1 -IdePath 'C:\path\to\GoLand' -JdkPath 'C:\path\to\jdk-25'
```

它会在 `build/sandbox/` 下创建测试 Git 仓库、独立 IDE 配置和测试插件，打开另一个 GoLand 实例，运行原生声明跳转与审阅历史检查。报告位于 `build/sandbox/smoke-report.txt`。关闭该测试实例后，可用 `-Reuse` 重跑；不会复制主 IDE 的配置或账户数据。

## 安装与卸载

GoLand → 设置 → 插件 → 齿轮 → 从磁盘安装插件，选择生成的 ZIP，并按 IDE 提示重启。随后在「提交」工具窗口的未暂存文件上打开 Diff，即可使用原有代码跳转操作。

卸载：设置 → 插件 → GoLand Diff Navigation → 卸载。审阅历史仅保存在内存中。

## 实现入口

- `StageNavigationHandler`：在原生导航最终打开源码之前接收 `SourceNavigationRequest`。
- `StageNavigationService`：选择暂存区目标、同步预览、加载后定位、取消失效操作。
- `IndexDeclarationHandler`：为内容相同的 INDEX 快照补充工作区解析上下文。
- `ReviewHistory`：保存同一预览内的文件、分组、侧别、位置及前进／后退路径。
- `ReviewHistoryAction` / `ReviewActionListener`：沿用键位并接入可用的原生导航按钮。

源码和构建产物不包含业务项目代码、IDE 二进制或账户凭据。
