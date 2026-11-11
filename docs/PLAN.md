# 练习页输入与阅读切换

目标：修复填空后键盘继续占据原文和图表阅读空间的问题。冻结的1.36发布文件不变；本次在独立工作树验证下一补丁。

先用真实Android键盘复现填空的IME动作、打开原文和列表拖动。调整仅限app的IeltsPracticeScreen与IeltsVisualCard：填空完成结束输入，明确阅读动作和外层列表拖动收起键盘；作文编辑和编辑框内部滚动保留输入。保存、提交、审核、出题契约不改。不会在滚动过程中添加新按钮或提示文字。

涉及测试：IeltsPracticeScreenTest，必要时新增IeltsKeyboardTrialTest，覆盖实际IME可见性、焦点、草稿文本、不误触提交，以及图表/原文切换。使用专用模拟器，不卸载或清除用户数据。先保留旧行为的失败证据，再修复并重跑；日间与Dracula、正常与200%字号检查实际渲染。

离线验证：`./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`；ADB直接运行定向Instrumentation及既有练习/导航回归，禁止connectedDebugAndroidTest。最后核对APK安装身份、当前用户数据/设置保全以及1.36发布文件摘要。

验收：阅读动作结束后IME隐藏且答案保持；进入作文输入时键盘和光标正常；内层编辑滚动、长按选择和程序滚动不被误清焦点；已有保存与回看回归通过。缺少真机与长期学习收益证据继续明确保留。

复现结果：原填空IME会跳到Task1，原文展开不收键盘。首轮按列表拖动清焦点会干扰作文，因此增加IeltsInputGesture，仅观察手势起点，不消费触摸事件。定向测试再按真实文字布局定位长按，避免200%字号时触到行尾空白。正常字号及夜间200%各5项、既有32项、同一真实题包夜间回看已通过；随后将内部编辑修饰符参数改为输入手势状态，消除新增Lint告警。最终源码在新目录构建并对准确APK完成对应回归后再交付。
