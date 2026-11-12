# Lexicon IDE

后续验证146：同一版本新增一次真实Harness内容核对与手机回看，5次请求完整返回，四题独立作答一致；图表数据和无背景复述的Task 2通过本次核对。程序与APK未改，原问题题包保留。详见[后续验证](docs/VALIDATION-146.md)。

当前开发快照为 **1.37.0-dev145**：增加提交后的个人复盘、题目存疑标记及下一篇的专项选择。保留1.36.1的键盘改进和1.36.0冻结版本。两个真实专项题包完成运行与回看，但独立内容核对分别发现顺序和背景事实问题；新守卫与验证边界见[本轮记录](VALIDATION.md)。尚未正式发布。

面向 Android 16 的外刊阅读与英语学习工具。中文界面，提供精读、略读与意群速读，在同一阅读流中查词、捕获语境、保存生词卡并用 FSRS-6 复习。

日间为暖白与陶土橙，夜间为 Dracula。正文可选等宽或衬线字体；字号、行距、字间距、暖纸色及阅读障碍友好字体均可调整。Zen 阅读通过段落聚焦减少干扰，文字选择始终基于原文位置。

## 能做什么

- 订阅外刊 RSS，在来源许可与能力范围内原生阅读。每日每源最多一篇，总共最多三篇。
- 导入本机 MDX 词典，离线查词。公开 APK 不附带朗文或其他商业词典。
- 使用个人 DeepSeek Key 生成语境卡、重点表达、阅读导图和辅助分析。文化背景解释可检索外部资料并显示引用。
- 进行意群节奏训练、理解检测和复盘。速度建议由阅读表现驱动，用户可以保留或撤销。
- 使用 FSRS-6 复习，导出 `.apkg` 单向内容快照。
- 通过个人电脑上的 DeepSeek Harness 生成原创雅思阅读题与 Task 2。Task 1 可从原文可比数字生成柱状图或数据表；数据不足时不生成图表。

雅思练习属于 AI 模拟材料，不是官方真题或 Band 预测。参考库收录公开样例格式与元数据，目前没有完整覆盖最近三年的剑桥雅思书籍。流程图、地图等 Task 1 类型尚未实现。详见 [验收范围](VALIDATION.md)。

## 构建

需要 JDK 17、Android SDK 37（构建 Compose 依赖）与 Android 16 / API 36 运行环境。Gradle Wrapper 和依赖版本已固定。

```sh
./gradlew :app:assembleDebug
```

已缓存依赖的环境可使用 `--offline`。产物位于 `app/build/outputs/apk/debug/app-debug.apk`。该包使用本机构建环境的调试签名。自行重新构建的签名可能与下载版不同，请先备份自己的学习数据。

公开源码不包含 API Key、个人词典、文章缓存或用户学习记录。首次运行可在设置中配置 Key，并用系统文件选择器导入词典。不要提交 `API.txt`、`.env`、MDX/MDD、数据库或配对文件。

## 模块

- `app`：Compose 界面、ViewModel 与 Android 入口。
- `domain`：纯 Kotlin 模型、精确文本范围、FSRS 与学习契约。
- `data`：Room、词典、网络、缓存和导出。
- `tools`：固定参考资料、受控 Harness 宿主及离线检查。

界面通过 Repository 访问数据。Harness 的生成、独立答题和解析审核使用不同 Session；只有完整审核通过才交付题包。一次格式纠正与一次内容修订分别限额，共享请求预算和期限。详见 [宿主使用](HARNESS.md)。

## 测试

```sh
./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
PYTHONPATH=tools python3 -m unittest discover -s tools -p 'test_*.py'
node tools/test_harness_ielts_plugin.mjs
```

部分原生 Harness 探针需要安装指定版本的桌面运行时；真实调用需单独提供个人 Key。模拟响应不计为线上成功率。设备测试在专用测试环境执行，不清空已有设备或卸载用户 App。

## 许可

原创代码按 [MIT](LICENSE) 开源。第三方字体、FSRS 参考实现及 LZO 解码代码保留各自许可，见 [第三方通知](licenses/NOTICE.md)。文章、外部检索内容和用户导入词典不受本项目 MIT 许可覆盖，也不随源码分发。
