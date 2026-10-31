# InterviewFlashcards

Java 后端面试卡片学习网站，当前已完成 P0-01 解析验证与规则决策，正式应用工程待 P0-02 建立。

- [设计文档](docs/设计文档.md)
- [开发文档与任务顺序](docs/开发文档.md)
- [P0-01 验收记录与回归基线](docs/fixtures/p0-01/README.md)
- [首版技术与数据规则决策](docs/decisions/0001-p0-01-baseline.md)

本地验证（Java 21、Maven 3.9+、Python 3.10+；首次运行需下载依赖）：

```powershell
./tools/pdf-probe/run.ps1 -RenderSamples
```

生成报告位于 `output/pdf-probe/report.md`。详细说明见 [PDF 验证工具](tools/pdf-probe/README.md)。
