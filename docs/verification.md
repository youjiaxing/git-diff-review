# 验证记录

环境：Windows，GoLand 2026.2.2.1 / GO-262.10315.160，JDK 25。

## 已完成

- `scripts/build-local.ps1` 使用本机 GoLand SDK 编译生产代码、测试代码并生成安装 ZIP。
- `ReviewHistoryTest`：10 项历史回归断言通过。
- `SmokeActivity` 在另一个 GoLand 进程中加载正式插件和独立测试插件：17 项集成断言通过。
- 独立实现审查发现的三处问题已修复，随后定向复核未发现有充分证据的剩余缺陷。

集成检查使用原生 `GotoDeclaration` action 验证：

1. 未暂存 `main.go` → 仅已暂存 `b.go`，定位函数声明。
2. 从 INDEX 中的 `b.go` 继续跳到未暂存 `c.go`。
3. Back / Back / Forward / Forward 恢复同一提交预览中的文件和位置。
4. 上述跳转期间不新增普通源码标签。
5. 无修改的 `clean.go` 使用普通源码编辑器，同时保留提交预览。
6. 从普通源码编辑器继续导航时，仍使用普通源码标签。
7. 已经打开的普通源码标签不会被关闭。

部分暂存历史另外用最终 `SourceNavigationRequest` 验证：从 INDEX 与工作区内容不同的文件出发，跳到另一个 Diff 后 Back，返回原 INDEX 的侧别、内容和坐标；没有将工作区新增的行号套到 INDEX 上。

测试源码、断言与测试仓库生成方式保留于 `src/smoke/` 和 `scripts/start-sandbox.ps1`，测试插件不会进入正式 ZIP。重新运行时，报告包含 `PASS 17 integration checks`。生产安装包只包含 `src/main`。

## 验证边界

- 原生 action 是通过 IDE ActionSystem 触发；尚未单独做物理鼠标 Ctrl+Click 输入验证。鼠标路径和候选弹窗仍由 IDE 原生处理。
- Unified、Combined、三栏冲突、分屏与历史提交不在首版接管范围内。
- INDEX 与本地内容不同的声明解析仍由 GoLand 原生处理，首版只在两者相等时补充工作区 PSI 上下文。
- IDE 当前通过的 `GotoDeclarationAction.findAllTargetElements` 在该 SDK 标有 `TestOnly`，导航扩展和暂存区接口也包含实验／内部 API。插件将版本锁在 `262.*`，升级主版本需重新适配和验证。
- `gradlew buildPlugin` 因本机访问 Gradle 插件仓库时 TLS 握手失败，未完成标准 Gradle 构建验证；本地 SDK 脚本已完成实际编译、测试和打包。
- GitHub Actions 仅设置手动触发；尚未在远端运行。

## 本地操作隔离

测试仓库、账户无关 IDE 配置和插件都在 `build/` 内；未读取或修改业务仓库的 Git index，也未将测试工具装进日常 IDE。
