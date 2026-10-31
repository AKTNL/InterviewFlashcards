# P0-01 PDF 解析验证工具

这是独立验证工具，不是正式后端工程或生产导入服务。Java 21 + Apache PDFBox 3.0.5 负责读取真实 PDF；Python 3.10+ 标准库负责目录/标题边界分析、报告和回归。运行不需要 Python PDF 库、数据库或前端。

在仓库根目录执行（PowerShell 5.1 / 7）：

```powershell
./tools/pdf-probe/run.ps1 -RenderSamples
```

首次运行需要 Maven 3.9+ 访问 Maven Central 下载依赖。脚本任何一步失败都会以非成功状态退出，不会自动重写基线。默认使用 `docs` 下唯一的 PDF；也可传入 `-Pdf '绝对路径'`，新文件会与已冻结的来源指纹比较，差异按失败报告。

手动执行步骤（Windows；Linux/macOS 将 classpath 中的 `;` 改为 `:`）：

```powershell
mvn -B -f tools/pdf-probe/pom.xml compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies '-DoutputDirectory=target/lib'
java '-Djava.awt.headless=true' -cp 'tools/pdf-probe/target/classes;tools/pdf-probe/target/lib/*' PdfProbe extract 'docs/Java+agent八股(高频版).pdf' output/pdf-probe/raw.json
python -X utf8 tools/pdf-probe/probe.py output/pdf-probe/raw.json
python -X utf8 -m unittest discover -s tools/pdf-probe -p test_probe.py -v
```

输出均位于 `output/pdf-probe/`（忽略提交）：

| 文件 | 内容 |
| --- | --- |
| `raw.json` | 来源 SHA-256、全部物理页、文字片段/坐标/字体、原始异常字符、前 6 页及其他页的链接目标、图片区域 |
| `cards.json` | 真实解析得到的候选、完整提取文字、逐页范围、追问位置、异常原因及原文保留指令 |
| `summary.json` | 数量、全部 158 题的起止位置、正文哈希、追问数、异常类型；用于冻结基线比较 |
| `validation.json` | 机器可读通过状态与差异列表 |
| `report.md` | 章节数量、异常统计、逐题边界和运行结果 |
| `rendered/page-NNN.png` | 使用 `-RenderSamples` 时生成的 11 页原文图像 |

任意候选的来源页都可以直接保留为图片。例如第 44 页流程图：

```powershell
java '-Djava.awt.headless=true' -cp 'tools/pdf-probe/target/classes;tools/pdf-probe/target/lib/*' PdfProbe render 'docs/Java+agent八股(高频版).pdf' output/pdf-probe/source-regions '44,45'
```

从 `cards.json` 读取 `sources[].page` 即可确定需要渲染的页；同时保留 `bbox` 供后续 PDF.js 定位。当前优先渲染整页，保证表格、代码、图片没有因文字框估计而被裁掉。生产链路在 P0-05 实现。

基线在 [docs/fixtures/p0-01](../../docs/fixtures/p0-01/README.md)：`expected.json` 是设计文档数量预期，`baseline.json` 是已审查来源的逐题解析快照。两者只作为校验，不参与生成候选；删除/修改数量不会让工具补造题目。变更来源或解析器时，先检查报告、样本与逐题差异，再人工评审更新基线。

限制：仅适配当前目录样式、未旋转页面和标题布局；不重建代码/表格语义，不 OCR、不摘要、不猜测缺失数字。无法解析目标、匹配标题或维持有序边界时明确失败。Type3 原始映射中的 NUL 与最终提取文字中的数字可同时存在，因此原始映射异常只作为风险证据，不能直接当作文字已丢失。
