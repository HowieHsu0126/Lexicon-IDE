# 个人电脑 Harness 宿主

Android 使用配对 HTTPS 服务提交当前阅读材料，电脑运行实际 DeepSeek Harness。Android 不执行桌面 CLI，也不会把其本机 Key 发送给宿主。常规阅读辅助仍由 App 自身的 DeepSeek Repository 完成。

当前适配版本为 macOS DeepSeek Harness `0.2.1-rc.5`，入口是 `/Applications/DeepSeek Harness.app/Contents/Resources/runtime/cli/bin/dsh`。需要 Node.js、Python 3、Xcode Command Line Tools 和 `sandbox-exec`。其他平台和运行时版本尚未验收。

先构建冻结的公开参考索引，再创建个人配对资料：

```sh
python3 tools/ielts_corpus.py build --help
python3 tools/ielts_pairing.py init --help
python3 tools/ielts_pairing.py export --help
python3 tools/serve_ielts.py --help
```

使用命令帮助中的参数指定新的私有目录、HTTPS 地址及证书。手机设置中选择「导入电脑连接文件」，核对公开证书指纹后确认连接。配对文件含访问凭据，不能上传 GitHub。默认监听 `127.0.0.1`；连接真实手机时需显式配置可达地址、证书 SAN 和监听地址。

Key 通过宿主的 `--api-key-file` 参数从本机文件读取，不放进命令参数内容。服务默认使用 Flash 生成和独立答题，Pro/high 检查解析。`--quality-revisions 1` 允许至多一次基于完整拒绝反馈的内容修订，两个审核随后使用新 Session 重新检查。

图表数据以有界十进制字符串保存。客户端从已引用原文范围定位数字，拒绝歧义、单位换算或猜补；独立审核再检查单位、分组和比较是否成立。数据不足会返回不可用，不构造虚拟趋势。

API 认证失败、取消或完成未知都不会自动重发。格式恢复限于已收到完整响应、只读工具全部完成、未尝试验证且无候选的情况，沿原 Session 至多一次。完成未知请求保留原 ID 对账。

模拟器配对验收使用 ADB 本机转发，不能证明真实局域网或 MagicOS 已通过。不要将冻结参考库描述为“近三年完整真题库”。

本版成功Task1试验显式使用 `--generation-model pro --review-model pro --review-reasoning off --explanation-reasoning low`。默认配置仍保持原值。该单例仅证明所记录链路完成，尚不支持整体成功率结论。
