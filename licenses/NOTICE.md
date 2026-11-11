# Third-party notices

- JetBrains Mono：SIL Open Font License，固定来源和 SHA-256 见 data/src/main/assets/asset-manifest.json。
- Source Serif 4：SIL Open Font License，同上。
- FSRS-6：MIT 参考实现 py-fsrs，固定 v6.3.2，对照说明和源摘要见 domain/reference/NOTICE.md。
- AndroidX/Compose/Room/WorkManager/Kotlin/OkHttp 使用各自 Apache-2.0 许可证；jsoup 使用 MIT；SQLite 使用其上游许可。固定依赖坐标见 gradle/libs.versions.toml，各依赖保留其发布的许可证和通知。
- 第六版朗文英英正文尚未提供。合成测试词库不是朗文内容；用户提供的个人 MDX 输入按下述单独约定处理。
- 出版方文章在运行时按来源配置获取；文章原文不是应用源码或演示资源，不随源码归档打包。
- MDX LZO1X 解码：`data/src/main/kotlin/dev/lexicon/data/mdict/Lzo1x.kt` 改编自 Airlift aircompressor 0.27 的 `LzoRawDecompressor`，Apache-2.0。原路径：`https://github.com/airlift/aircompressor/blob/0.27/src/main/java/io/airlift/compress/lzo/LzoRawDecompressor.java`。本地副本见 `Airlift-Apache-2.0.txt`；改动是移除 Unsafe、使用有界字节数组并保留原始 LZO 指令语义。
- 个人构建输入 `data/dic/*.mdx` 可随个人 APK 打包；该数据不属于开源代码或测试合成材料，源码归档排除词典正文。当前用户提供文件的 MDX 头部标注为「朗文当代英语词典英汉双解第4版」，不是第六版英英词典。词典媒体（MDD）不打包或加载。
- OpenDyslexic Regular：SIL Open Font License 1.1，固定 v0.91.12；许可及来源 SHA-256 位于 app/src/main/assets/licenses/OpenDyslexic-OFL.txt 与 OpenDyslexic-source.json。

## Runtime cultural-background evidence

Cultural-background retrieval uses the public English Wikipedia MediaWiki API. Retrieved article introductions remain attributed to Wikipedia contributors under CC BY-SA 4.0. Each result records the source title, page/revision URL, retrieval time and license, and the reading UI makes these available with the actual excerpt. The APK does not bundle a Wikipedia corpus. The source dialog separates the retrieved excerpt from AI explanation and context inference. Source and revision links preserve contributor-history access.

公开 GitHub 源码与发布 APK 不打包个人 MDX/MDD。用户可在设备设置中导入自己有权使用的词典；个人构建仍可从本机 data/dic 输入生成资产。
