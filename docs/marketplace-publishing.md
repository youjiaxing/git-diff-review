# JetBrains Marketplace 发布准备

核查日期：2026-11-06。本文记录发布流程与当前仓库差距，不代表已提交或已获审核通过。

## 首次发布流程

1. 使用个人 JetBrains Account 登录 Marketplace，从账户菜单选择 Upload plugin。
2. 首次发布需接受 Marketplace Developer Agreement，并创建 Vendor profile。
3. 上传插件发布 ZIP，填写许可证、源码地址、标签与插件资料。选择开源许可证时需提供源码链接。
4. 提交审核；首次插件发布必须通过网页手动上传。后续可以继续手动上传，也可以使用 Marketplace token 配合 Gradle `publishPlugin`。

官方来源：

- [Uploading a new plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
- [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)

## 页面资料

描述应以英文为主，可在英文之后补中文；可添加截图、使用说明、问题反馈与源码链接。图标可选，但建议提供原创且易识别的图标；不要使用默认模板图标或仿制 JetBrains 产品标识。更新记录可通过 `plugin.xml` 的 `change-notes` 提供。

官方来源：[Best practices for listing your plugin](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)。

## 当前项目状态

- 名称：Git Diff Review；ID：`io.github.youjiaxing.git-diff-review`；版本：0.3.1。
- 仓库已有 MIT LICENSE，已有英文描述和可安装 ZIP。
- 本地 SDK 构建与 69 项独立 GoLand 集成检查已通过，详见 [验证记录](verification.md)。这些检查不等同于 Plugin Verifier，也不代表 Marketplace 审核完成。
- 目前仅验证 GoLand 262；插件声明支持 `262.*`。上架前应确认实际兼容的 IDE 与具体版本，必要时在 Marketplace 管理后台限制兼容产品，不应仅凭共用平台宣称通用。
- 本轮检查未发现插件图标和 `change-notes`；需准备页面截图、使用说明及反馈链接。
- 当前 GitHub Actions 仅构建 ZIP，尚未配置 Marketplace 自动发布。首次手动上架不以自动发布为前提。
- 当前描述符有 vendor 网站但没有 email；审核规范要求有效的发布者网站和邮箱，上架资料需补齐联系方式。

产品兼容性由描述符推导，也可以在管理后台调整：[Product compatibility](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#product-compatibility)。

## 发布渠道选择

默认 Stable 渠道面向普通 Marketplace 用户；alpha、beta、eap 等自定义渠道需要用户在 IDE 添加相应自定义插件仓库。自定义渠道适合测试分发，不能将它描述成默认插件市场中的直接安装体验。Hidden 可控制审核通过后暂不公开。

官方来源：

- [Custom release channels](https://plugins.jetbrains.com/docs/marketplace/custom-release-channels.html)
- [Uploading a new plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)

## 技术检查与审核

免费、开源插件及每次更新同样经过自动和人工审核，官方不保证完成期限。审核规范要求每次上传使用 Plugin Verifier 确认二进制兼容；本地可使用 Gradle `verifyPlugin`，也可使用独立 Verifier。应对实际支持的 IDE 和版本检查，并另行保留功能测试。

当前发布前应逐项核对生产路径的 API：

- `@ApiStatus.Internal`：官方禁止插件使用，并将内部 API 使用规则列为审核条件。需核对具体符号、版本和官方迁移页的替代或例外。
- `@ApiStatus.Experimental`：可能变化或删除；不能直接推断使用一次就必然拒审。
- `@TestOnly`：只供测试使用。当前 `IndexDeclarationHandler` 调用的 `GotoDeclarationAction.findAllTargetElements` 已在验证记录注明此标注，应在发布前替换或重新设计调用路径；本次没有运行 Verifier，不能断言其具体报告结果。

官方来源：

- [Approval Guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)
- [Verifying Plugin Compatibility](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
- [Internal API Migration](https://plugins.jetbrains.com/docs/intellij/api-internal.html)
- [TestOnly 官方注解源码](https://github.com/JetBrains/java-annotations/blob/master/src/jvmMain/java/org/jetbrains/annotations/TestOnly.java)

## 签名说明

官方发布页要求发布前确保签名，但签名专页区分作者签名与 Marketplace 分发签名：Marketplace 会签署分发包，作者公钥上传仍标注尚未开放，未提供作者证书时 Gradle `signPlugin` 会跳过。文档措辞存在不一致，因此不将购买证书或作者自行签名列为已证实的硬性准入要求。正式提交前按当时上传页面和官方确认处理。

官方来源：[Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)、[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)。
