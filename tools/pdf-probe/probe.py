"""Interpret PDFBox evidence; Python 3.10+ standard library only. Coordinates: top-left points."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[2]
ALIASES = {
    "Java篇": "Java 基础", "JVM篇": "JVM", "JUC篇": "JUC / 并发编程",
    "MySQL篇": "MySQL", "Reids篇": "Redis", "Spring篇": "Spring",
    "Mybatis篇": "MyBatis", "消息队列篇": "消息队列", "计算机网络篇": "计算机网络",
    "操作系统篇": "操作系统", "agent篇": "Agent", "RAG篇": "RAG", "LLM篇": "LLM",
}
AI_CHAPTERS = {"Agent", "RAG", "LLM"}


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalized(text):
    return "".join(c for c in text if not c.isspace() and unicodedata.category(c) != "Cf")


def overlaps(a, b):
    return min(a[2], b[2]) > max(a[0], b[0]) and min(a[3], b[3]) > max(a[1], b[1])


def body_spans(page):
    # Only strip an isolated physical page number in the known footer band.
    return [s for s in page["spans"] if not (
        s["bbox"][1] > page["height"] - 35 and s["text"].strip() == str(page["page"]))]


def find_title(page, title, target):
    """Match wrapped titles by text; destination coordinates disambiguate repetitions."""
    spans = sorted(body_spans(page), key=lambda s: (round(s["bbox"][1] / 3), s["bbox"][0]))
    wanted = normalized(title)
    candidates = []
    for i, first in enumerate(spans):
        joined = ""
        for last in spans[i:i + 6]:
            joined += normalized(last["text"])
            if not wanted.startswith(joined):
                break
            if joined == wanted:
                candidates.append((first["bbox"][1], last["bbox"][3]))
                break
    if not candidates:
        raise ValueError(f"p{page['page']}: 正文标题未匹配: {title}")
    top = target.get("topPdf")
    anchor = page["height"] - top if top is not None and top >= 0 else 0
    return min(candidates, key=lambda match: abs(match[0] - anchor))


def parse_toc(raw, toc_pages=6):
    entries = []
    errors = []
    chapter = None
    raw_chapter = None
    for page in raw["pages"][:toc_pages]:
        events = [(s["bbox"][1], 0, s) for s in page["spans"] if normalized(s["text"]).endswith("篇")]
        events += [(link["rect"][1], 1, link) for link in page["links"]]
        for _, kind, item in sorted(events, key=lambda event: event[:2]):
            if kind == 0:
                raw_chapter = normalized(item["text"])
                chapter = ALIASES.get(raw_chapter, raw_chapter)
                if raw_chapter not in ALIASES:
                    errors.append(f"未知章节: {raw_chapter}")
                continue
            spans = [s for s in page["spans"] if overlaps(s["bbox"], item["rect"])]
            label = " ".join(s["text"].strip() for s in spans)
            target = item["target"]
            entry = {"id": f"q{len(entries) + 1:03}", "tocPage": page["page"],
                     "tocRect": item["rect"], "title": label, "rawChapter": raw_chapter,
                     "chapter": chapter, "module": "AI 扩展" if chapter in AI_CHAPTERS else "Java 后端基础",
                     "destinationName": item["name"], "target": target,
                     "tocGlyphIssues": [g for s in spans for g in s["glyphIssues"]]}
            try:
                if not chapter or not label or not target or not toc_pages < target["page"] <= raw["pageCount"]:
                    raise ValueError(f"目录条目缺失标题/章节或目标不合法: {entry['id']}")
                dest_page = raw["pages"][target["page"] - 1]
                if dest_page["rotation"] != 0:
                    raise ValueError(f"尚未适配旋转页: {target['page']}")
                start, title_end = find_title(dest_page, label, target)
                entry.update(start={"page": target["page"], "y": start}, titleEndY=title_end)
            except ValueError as exc:
                errors.append(str(exc))
            entries.append(entry)
    starts = [(e["start"]["page"], e["start"]["y"]) for e in entries if "start" in e]
    if any(a >= b for a, b in zip(starts, starts[1:])):
        errors.append("目录目标未严格递增或出现重复边界；禁止静默重排/合并")
    return entries, errors


def slice_sources(pages, start, end):
    """Half-open [start,end) supports multiple cards on a physical page."""
    spans = []
    for page in pages[start["page"] - 1:end["page"]]:
        low = start["y"] if page["page"] == start["page"] else 0
        high = end["y"] if page["page"] == end["page"] else page["height"]
        if low >= high:
            continue
        text = [s for s in body_spans(page) if low <= s["bbox"][1] < high]
        images = [image for image in page["images"] if overlaps(image["bbox"], [0, low, page["width"], high])]
        if page["page"] == end["page"] and not text and not images:
            # The blank area above the next title is not part of the previous answer.
            continue
        # Keep empty physical spans for traceability, with no generated answer text.
        spans.append({"page": page["page"], "bbox": [0, low, page["width"], high],
                      "textSpans": text, "images": images})
    return spans


def make_card(entry, end, pages):
    sources = slice_sources(pages, entry["start"], end)
    text = "\n".join(s["text"] for source in sources for s in source["textSpans"])
    flags = []
    followups = []
    for source in sources:
        for span in source["textSpans"]:
            if re.match(r"^追加\s*[:：]", span["text"].strip()):
                followups.append({"page": source["page"], "bbox": span["bbox"], "title": span["text"].strip()})
            if span["glyphIssues"]:
                flags.append({"kind": "GLYPH_MAPPING", "page": source["page"], "bbox": span["bbox"], "evidence": span["glyphIssues"]})
            controls = Counter(f"U+{ord(c):04X}" for c in span["text"] if c == "\ufeff" or c == "\ufffd" or (ord(c) < 32 and c not in "\n\t\r"))
            if controls:
                flags.append({"kind": "SPECIAL_CHARACTER", "page": source["page"], "bbox": span["bbox"], "evidence": dict(controls)})
        if source["images"]:
            flags.append({"kind": "IMAGE_REQUIRES_SOURCE", "page": source["page"], "evidence": source["images"]})
        if not source["textSpans"] and not source["images"]:
            flags.append({"kind": "EMPTY_PAGE", "page": source["page"]})
    if entry["tocGlyphIssues"]:
        flags.append({"kind": "TOC_GLYPH_MAPPING", "page": entry["tocPage"], "evidence": entry["tocGlyphIssues"]})
    flags.append({"kind": "LAYOUT_REQUIRES_SOURCE", "reason": "段落、代码/表格语义未自动重建；保留全部文字位置和来源页段，逐章核对"})
    return {**entry, "endExclusive": end, "sources": sources, "text": text,
            "contentSha256": hashlib.sha256(text.encode()).hexdigest(),
            "followups": followups, "flags": flags,
            "fallback": {"type": "PDF_SOURCE_REGIONS", "requiresReview": True,
                         "reason": "按 sources 的物理页和 bbox 渲染原文；不得用摘要替换"}}


def analyze(raw, expected, toc_pages=6):
    entries, errors = parse_toc(raw, toc_pages)
    cards = []
    if not errors:
        for i, entry in enumerate(entries):
            end = entries[i + 1]["start"] if i + 1 < len(entries) else {"page": raw["pageCount"], "y": raw["pages"][-1]["height"]}
            cards.append(make_card(entry, end, raw["pages"]))
    chapters = dict(Counter(e["chapter"] for e in entries))
    modules = dict(Counter(e["module"] for e in entries))
    for key, actual in [("pageCount", raw["pageCount"]), ("questionCount", len(entries)), ("chapters", chapters), ("modules", modules)]:
        if actual != expected[key]:
            errors.append(f"{key} 差异: expected={expected[key]!r}, actual={actual!r}")
    blanks = [p["page"] for p in raw["pages"] if not body_spans(p) and not p["images"]]
    summary = {"parserVersion": raw["parserVersion"], "sha256": raw["sha256"], "pageCount": raw["pageCount"],
               "questionCount": len(entries), "chapters": chapters, "modules": modules,
               "tocLinkCounts": [len(p["links"]) for p in raw["pages"][:toc_pages]],
               "destinationTypes": dict(Counter(e["target"]["type"] if e["target"] else "UNRESOLVED" for e in entries)),
               "samePageStarts": {str(p): n for p, n in Counter(e["start"]["page"] for e in entries if "start" in e).items() if n > 1},
               "blankPages": blanks, "imagePages": [p["page"] for p in raw["pages"] if p["images"]],
               "flagCounts": dict(Counter(flag["kind"] for c in cards for flag in c["flags"])),
               "cards": [{"id": c["id"], "title": c["title"], "chapter": c["chapter"],
                          "start": c["start"], "endExclusive": c["endExclusive"],
                          "sourcePages": [s["page"] for s in c["sources"]],
                          "contentSha256": c["contentSha256"], "followupCount": len(c["followups"]),
                          "flagKinds": sorted(set(f["kind"] for f in c["flags"]))} for c in cards]}
    hashmap = [c for c in cards if "HashMap" in c["title"] and "扩容" in c["title"]]
    if len(hashmap) != 1 or [s["page"] for s in hashmap[0]["sources"]] != [10, 11, 12]:
        errors.append("HashMap 验收样本应为一题且来源覆盖物理页 10、11、12")
    return {"summary": summary, "cards": cards, "errors": errors, "expected": expected}


def markdown(result):
    s = result["summary"]
    lines = ["# P0-01 PDF 解析运行报告", "", f"- 解析器：`{s['parserVersion']}`", f"- 来源 SHA-256：`{s['sha256']}`",
             f"- 物理页数：{s['pageCount']}；目录主问题：{s['questionCount']}",
             f"- 前 6 页链接数：{s['tocLinkCounts']}", f"- 目标类型：{s['destinationTypes']}",
             f"- 同页主问题起点：{s['samePageStarts']}（本样本无同页多题，另有构造回归）",
             f"- 仅页码空白页：{s['blankPages']}；含图片页：{s['imagePages']}",
             "", "## 数量核对", "", "| 章节 | 设计预期 | 实际题数 | 差值 |", "| --- | ---: | ---: | ---: |"]
    chapter_names = dict.fromkeys([*result["expected"]["chapters"], *s["chapters"]])
    for name in chapter_names:
        expected = result["expected"]["chapters"].get(name, 0)
        actual = s["chapters"].get(name, 0)
        lines.append(f"| {name} | {expected} | {actual} | {actual - expected} |")
    lines += ["", f"模块：{s['modules']}", "", "## 异常与保留", "",
              "所有候选均保留原始文字位置、物理页段及 PDF 原文区域保留指令，带待检查标记。",
              "代码和表格仅保留原始文本/坐标，不声称已重建语义。特殊字符保留原值，不猜数字，不自动纠错。",
              "`GLYPH_MAPPING` 是字体映射风险；最终文字可能通过 ActualText 等机制保留数字，不能据此推断数字已丢失。", ""]
    lines += [f"- {kind}：{count} 处" for kind, count in s["flagCounts"].items()]
    lines += ["", "## 题目与半开来源范围", "", "位置为从页面左上角起算的 point；终点标题不归本题。完整页段见 cards.json。", "",
              "| ID | 章节 | 标题 | 起点（物理页:y） | 排他终点 | 追问数 |", "| --- | --- | --- | --- | --- | ---: |"]
    for c in s["cards"]:
        start, end = c["start"], c["endExclusive"]
        lines.append(f"| {c['id']} | {c['chapter']} | {c['title'].replace('|', '/')} | {start['page']}:{start['y']:.2f} | {end['page']}:{end['y']:.2f} | {c['followupCount']} |")
    lines += ["", "## 验证结果", ""]
    lines += [f"- FAIL：{error}" for error in result["errors"]] if result["errors"] else ["PASS：设计数量、标题边界、HashMap 跨页和已冻结基线均一致。"]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "output/pdf-probe")
    parser.add_argument("--expected", type=Path, default=ROOT / "docs/fixtures/p0-01/expected.json")
    parser.add_argument("--baseline", type=Path, default=ROOT / "docs/fixtures/p0-01/baseline.json")
    args = parser.parse_args()
    result = analyze(read_json(args.raw), read_json(args.expected))
    if args.baseline.exists():
        baseline = read_json(args.baseline)
        if result["summary"] != baseline:
            result["errors"].append("冻结基线有差异（含源 SHA、逐题边界、正文哈希、追问和异常）；请对比 summary.json，不自动接受新基线")
    else:
        result["errors"].append(f"冻结基线不存在: {args.baseline}")
    write_json(args.output / "summary.json", result["summary"])
    write_json(args.output / "cards.json", result["cards"])
    write_json(args.output / "validation.json", {"passed": not result["errors"], "errors": result["errors"]})
    (args.output / "report.md").write_text(markdown(result), encoding="utf-8")
    print(f"{'FAIL' if result['errors'] else 'PASS'}: {len(result['cards'])} candidates; report: {args.output / 'report.md'}")
    for error in result["errors"]:
        print(error, file=sys.stderr)
    return int(bool(result["errors"]))


if __name__ == "__main__":
    raise SystemExit(main())
