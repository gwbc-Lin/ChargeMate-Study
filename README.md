# ChargeMate

Android 无线充电时钟（含 Spine Runtimes，所有用户须持有有效 Spine Editor 许可）：默认以无线充电触发角色与时钟，可在设置中额外开启有线充电；稳定断开后退出。离线运行，无账号、广告或网络权限。

当前版本：**0.8.0-public**，Android 10 / API 29 及以上。

> **用途声明：本项目仅供学习与技术研究。** 它不是可商用的产品，也不授予任何第三方素材、运行库、角色或商标的使用权；请勿用于商业发布、应用商店上架、二次分发或冒充官方应用。详细边界见下方「用途与授权边界」。

## 用途与授权边界（仅学习）

- 公开的代码用于学习 Android 开发：Compose 界面、前台服务与开机广播、无线充电会话状态机、WebView 内的 Live2D / Spine 渲染与性能控制等，适合阅读、实验和二次研究。
- 学习目的**不能替代许可**。Spine Runtimes、Live2D Cubism Core、PixiJS 及任何第三方模型与角色素材，其版权和许可归各自权利人；即使出现在本仓库（含历史提交）中，也不属于本项目授权范围，也不代表已被授权公开分发。
- 本项目不提供、也不鼓励使用游戏或动漫素材；不提供模型提取、解包、绕过保护的任何工具或指引。
- 商业使用、对外分发、应用商店上架，需自行取得全部涉及的代码、运行库、模型与角色许可，并自行承担合规责任。
- 若你发现仓库内仍有不应公开的第三方内容，请提交 Issue 或直接联系维护者删除，不要以"学习用途"主张其可用。

## 公开版包含什么

- 两个项目原创基础角色：充电机器人、充电猫（静态 SVG，项目 MIT 许可）。
- PNG / Spine 导入、角色选择、大数字时钟、横竖屏布局、电量提示；Live2D 为可选开发集成，运行库需自备。
- 全屏界面左滑下一位、右滑上一位，循环切换并保存选择；点击互动与滑动分开处理。
- 全屏竖直上滑退出，与返回键、关闭按钮等价，并写入屏幕下方提示文案。
- 仓库不附带任何第三方角色模型；默认角色为原创充电机器人，可在应用内导入自己的 PNG / Spine 模型。
- 无线触发默认支持，有线充电（AC / USB / 供电底座）开关默认关闭。
- 无线充电触发、前台监听、通知入口、可选系统屏保、温度降载与退出抑制。
- 可选手机播放音频频谱；默认关闭，授权后只分析输出频谱，不录音、不保存音频。

**没有内置动画模型怎么办？** 安装后默认显示原创充电机器人；想换成动画角色，按下文「动态模型」一节在应用内导入自己的 Spine ZIP（或 PNG 图片）。仓库不提供、也不代收任何第三方角色模型，模型与运行库的授权需自行取得。

**本项目包含 Spine Runtimes；按许可的逐用户持证分支提供，每位产品用户都必须取得自己的有效 Spine Editor 许可。此条件也适用于只使用 PNG 的用户，不是仅要求模型作者持证。** 没有许可请不要使用本版本。项目不代购或授予许可，首次启动要求用户确认，确认不能替代实际持证。

- Spine Runtimes Copyright (c) 2013–2025, Esoteric Software LLC。随源码和 APK 提供[当前完整条款](app/src/main/assets/live2d/licenses/Spine-Runtimes-2025-LICENSE.txt)及[原集成条款副本](app/src/main/assets/live2d/licenses/Spine-LICENSE.txt)，不是只在 README 写一句致谢。官方说明：[Spine Runtimes License](https://esotericsoftware.com/spine-runtimes-license)。
- 不包含游戏、动漫角色；内置模型仅为项目原创基础角色。用户模型仍需原作者授权。
- Live2D 导入、渲染代码及本地开发构建保留。**Live2D 发行许可尚待发布者补齐，不以 Spine 的逐用户条款替代。** Live2D Core 与运行库不在仓库内，放在本地忽略目录；启用 Live2D 的 Release 构建仍会被许可检查阻止。

购买模型、模型个人使用许可、运行库许可和应用发行许可是不同的权利。

本项目自有代码与原创 SVG 使用 [MIT](LICENSE)。第三方组件与素材不因本项目开源而变成 MIT 或无版权素材，见 [第三方说明](app/src/main/assets/live2d/THIRD_PARTY_NOTICES.md)。

## 使用与权限

1. 打开应用，按需开启“充电时自动显示”。
2. 允许通知，以便了解监听状态并在系统阻止自动弹出时点击进入。
3. “设置”中按需允许打断其他应用、息屏亮屏、锁屏显示；新配置默认允许在其他应用上显示、息屏亮屏和锁屏上显示；可在设置中关闭。不会解锁安全锁屏。
4. 自动显示失败时，先查看“运行详情 → 运行诊断与日志”。自启动、后台运行和后台弹出权限属于不同能力，不能只授予其中一项就认定全部可用。
5. 手动关闭后本次充电不再弹出；重新连接或点击“恢复显示”继续。进程恢复旧会话时可能需要通过通知确认。

可选的“显示在其他应用上层”仅通过系统授权页面申请；应用不绘制常驻悬浮窗。电池优化入口打开系统设置列表，由用户选择。不开启无障碍服务、不请求 root、不修改全局亮度、不修改系统安全设置、不做循环拉起或精确闹钟保活。

各品牌路径、设备边界与验证情况见 [Android 适配说明](docs/ANDROID_COMPATIBILITY.md)。没有无线充电硬件的手机可用手动全屏模式；不兼容 Android APK 的系统不在此版本范围内。

## 自己添加角色：PNG（公开版可用）

1. 从作者处取得允许你在本机使用的 PNG 立绘。透明背景效果较好；不要把仅可浏览的图片当成可再分发素材。
2. 将文件保存到手机“下载”等位置，不必放进任何 Android 系统目录。
3. 首页点“导入素材 → 选择 PNG 文件”，通过系统文件选择器选中文件。
4. 导入成功后进入角色库预览，点“使用此角色”；导入和选择是两个独立步骤。
5. 打开“全屏显示”检查横竖屏效果。原文件随后可以移走，应用使用私有目录中的副本。

导入后，在“选择角色”中选中图片，点“调整图片位置与大小”。支持拖动、双指缩放及滑块微调；横屏、竖屏分别保存，取消不会覆盖已保存构图，“重置当前方向”恢复完整适配。手动放大或移动可能使部分图片超出屏幕。

PNG 上限 32 MiB、任一边不超过 8192 像素、总像素不超过 16,777,216。过大的图片请先在图片编辑器中缩小。应用不申请广泛存储权限，不会自动上传或分享导入文件。卸载应用会清除导入副本。

## 动态模型：Spine 用户持证；Live2D 发行许可待补齐

没有相应许可时，不能靠“用户自己下载模型”绕过运行库发行条件。Spine 构建要求每位用户真实持有许可；Live2D 暂限适用条款下的开发验证，正式发行待补齐许可。下面分别说明导入格式。

### Live2D ZIP

可选适配器针对 Cubism 2 和 Cubism 3/4 导出格式；不承诺支持较新模型的全部特性。不是把 Editor 工程、网页链接或视频改名成 ZIP。

一个 ZIP 只放一个模型配置及其引用文件，文件名大小写和相对路径保持原样：

```text
MyCharacter/
  MyCharacter.model3.json
  MyCharacter.moc3
  textures/texture_00.png
  motions/idle.motion3.json
  expressions/smile.exp3.json
  MyCharacter.physics3.json
```

Cubism 2 使用 `model.json`、`.moc`、贴图和配置引用的动作/物理等文件。配置未引用的可选文件不必凭空创建；配置已引用的资源必须齐全，包括音频引用（运行时禁用音频播放）。ZIP 可以有外层文件夹，但不要把多个模型或多个姿势混在一个包中。

启用对应运行库的构建中：导入素材 → 选择模型 ZIP → 检查成功 → 角色库预览 → 使用此角色。

### Spine ZIP

适配版本为 3.7、3.8、4.0、4.1，需与运行库匹配；不把 4.2 以上版本当作已支持。

```text
MyCharacter/
  character.skel       # 或导出的骨骼 JSON，二选一
  character.atlas
  texture_00.png
  texture_01.png       # 多页图集需带齐所有引用贴图
```

骨骼文件与 `.atlas` 放在同一目录，贴图位置按 atlas 中的路径保留。不同姿势分别打包。仅有 PNG 图集无法还原骨骼动画。若颜色或透明边缘异常，先检查 atlas 的 PMA 标志和原始贴图，不要直接修改角色肤色来补偿渲染错误。

### 常见导入失败

| 提示或现象 | 处理 |
| --- | --- |
| 当前构建未启用运行库 | 检查运行库是否已启用；Spine 必须持证，Live2D 发行前需补齐授权 |
| 多个模型配置 / 多份 atlas | 每次只打包一个完整模型或姿势 |
| 缺少资源 | 按配置和 atlas 的相对路径补齐，保留文件名大小写 |
| 文件类型不支持 | 去掉网页、脚本、可执行文件；不接受 JS、HTML、SVG 用户导入 |
| ZIP 过大 / 图片过大 | 缩小贴图；解压总量最多 128 MiB、单文件最多 64 MiB、最多 2048 个条目 |
| JSON / atlas 过大 | 这类文件最多 2 MiB，JSON 深度最多 48 层 |
| 内存或贴图受限 | 单图最多 16,777,216 像素、模型贴图合计最多 33,554,432 像素 |

路径穿越、网络引用、重复路径和可执行文件会被拒绝。导入校验可以降低风险，不能使来源不明的文件自动变得可信。

## 可以从哪里寻找素材

优先阅读作者和平台条款，确认下载的是导出模型而不是工程预览；免费标价不等于允许在应用中分发。

| 来源 | 用途和授权提醒 |
| --- | --- |
| [Live2D 官方示例](https://www.live2d.com/en/learn/sample/) / [CubismWebSamples](https://github.com/Live2D/CubismWebSamples) | 原型验证与学习；Haru、Hiyori、Mao 等有具体条款，不能视为公共领域 |
| [nizima](https://nizima.com/) | 创作者发布或销售的模型，逐项核对使用范围、署名与再分发限制 |
| [BOOTH Live2D](https://booth.pm/en/search/Live2D) | 作者商店与免费模型，个人使用许可通常不自动包含重新打包发行权 |
| [imuncle/live2d](https://github.com/imuncle/live2d) | 早期开发参考过的模型集合；收集仓库不代表原角色权利人的再分发授权 |
| [Nikke-db 仓库](https://github.com/Nikke-db/Nikke-db.github.io) / [Visualiser](https://nikke-db.pages.dev/visualiser) | 早期验证的参考来源；本项目不提供游戏素材、不提供提取或绕过保护的工具，不据此宣称素材获授权 |

## 构建

使用 JDK 25、Android SDK 37、Python 3.10+、Node.js 与仓库 Gradle Wrapper。设置本机 `local.properties` 的 `sdk.dir`，不要提交此文件或签名密钥。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test tools/stage-fit.test.cjs tools/playback-policy.test.cjs tools/landscape-layout.test.cjs tools/frame-loop.test.cjs
python tools/check_public_release.py --apk app/build/outputs/apk/debug/app-debug.apk --allow-live2d-development
```

Linux/macOS 使用 `./gradlew`。默认 `spineEnabled=true`、`live2dEnabled=false`：仓库自带 Spine 运行库，干净源码可直接构建；Live2D 运行库不在仓库内，需要时先按下一节准备，再用 `-Plive2dEnabled=true` 构建。

Debug APK 仅供开发测试。Live2D 启用时，未明确传入 `-Plive2dPublicationApproved=true` 会阻止 Release 打包；该参数只能在发布者实际取得适用发行权之后使用，不代表已经获得许可。当前不得用它绕过待补许可事项。Release 不含私钥，发行前需自行签名。

```powershell
# 不包含 Live2D 的 Spine 版本：所有用户仍须持有 Spine Editor 许可
.\gradlew.bat :app:assembleRelease -Plive2dEnabled=false
python tools/check_public_release.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
```

核心回归集保持 47 项 Kotlin/JVM 与 8 项 JS；不为固定名称、常量和重复分支扩增测试。

### Live2D 本地运行库准备与后续发行

先阅读 [Live2D 可扩展性应用规则](https://www.live2d.com/en/sdk/license/expandable/) 与 [Spine Editor / Runtime 条款](https://esotericsoftware.com/spine-editor-license)。**设置构建参数不是授权手续。** Live2D 对可导入自定义模型的应用另有审批要求；不能简单套用个人免费豁免。

自行合法取得运行库，整理为本地目录 `vendor/` 和 `licenses/`。Live2D 适配器使用 `pixi.min.js`（6.5.10）、`cubism4.min.js` / `cubism2.min.js`（pixi-live2d-display 0.4.0）、`live2dcubismcore.min.js`、`live2d.js`；Spine 所需的 `pixi7.min.js`（7.4.3）、`pixi-spine.js`（4.0.6）已随源码附带，各自许可不同。保留完整原始许可及版权头。旧 Core 兼容性和来源也须自行核验。

```powershell
python tools/prepare_optional_runtime.py --kind live2d --source "D:/MyLicensedLive2D" --accept-license
.\gradlew.bat :app:assembleDebug -Plive2dEnabled=true
# 没有可用 Live2D 运行库时，可用 -Plive2dEnabled=false 构建 Spine 版本
```

Live2D 文件复制到忽略的 `app/optional/`，不进入公开源码导出。准备脚本只确认运行库条款接受，不确认发行审批，也不下载专有文件。发行前需独立确认 Live2D 许可，再使用明确的发行确认参数构建并审查；当前发行状态见审查记录。

## 仓库内容与历史

本仓库是**无历史重建**的公开版本：只包含项目自有代码、原创 SVG 角色、Spine 运行库与许可文本，不含任何第三方角色模型、Live2D Core 或游戏素材；旧私有仓库保留原状，不对外公开。

工作树里可能仍有本地保存的素材（`asset/`、`app/src/spine/assets/live2d/spine/`、`player/`、`.tmp-*`），它们已被 `.gitignore` 排除，不会进入仓库，也不会随源码包分发。

需要重新导出时：

```powershell
python tools/export_public_source.py
```

脚本导出当前工作树到 `dist/ChargeMate-public-source.zip`，不含 `.git`、本地缓存、可选运行库、签名文件和历史对象。

本机旧素材仅备份在忽略的 `tools/cache/`，导出和打包均不包含。审查结果及尚未完成的跨品牌实测见 [发行审查记录](docs/RELEASE_REVIEW.md)。
