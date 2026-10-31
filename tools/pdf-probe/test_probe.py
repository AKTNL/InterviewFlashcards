import copy
from collections import Counter
import unittest

from probe import ROOT, analyze, body_spans, find_title, make_card, parse_toc, read_json, slice_sources


def span(text, y, size=12, faults=None):
    return {"text": text, "bbox": [48, y, 540, y + size], "fontSize": size,
            "fonts": ["fixture-font"], "glyphIssues": faults or []}


def page(number, spans):
    return {"page": number, "width": 612, "height": 792, "rotation": 0,
            "spans": spans, "links": [], "images": []}


class BoundaryTests(unittest.TestCase):
    def test_wrapped_title_and_repeated_body_text_use_target(self):
        p = page(2, [span("长标题", 60), span("第二行？", 90), span("正文", 120),
                     span("长标题第二行？", 400)])
        self.assertEqual((60, 102), find_title(p, "长标题 第二行？", {"topPdf": 732}))

    def test_same_page_cards_are_half_open_and_keep_followup(self):
        toc = page(1, [span("Java篇", 20), span("第一题？", 60), span("第二题？", 100)])
        toc["links"] = [{"rect": [48, y, 540, y + 12], "name": str(y),
                         "target": {"page": 2, "topPdf": 732}} for y in [60, 100]]
        p = page(2, [span("第一题？", 60), span("第一答案", 100), span("追加：为何？", 130),
                     span("第二题？", 300), span("第二答案", 340)])
        entries, errors = parse_toc({"pages": [toc, p], "pageCount": 2}, toc_pages=1)
        self.assertFalse(errors)
        a = make_card(entries[0], entries[1]["start"], [toc, p])
        b = make_card(entries[1], {"page": 2, "y": 792}, [toc, p])
        self.assertIn("第一答案", a["text"])
        self.assertNotIn("第二题", a["text"])
        self.assertNotIn("第一答案", b["text"])
        self.assertEqual(1, len(a["followups"]))
        self.assertEqual(300, a["sources"][0]["bbox"][3])

    def test_empty_top_of_next_page_does_not_extend_previous_source(self):
        pages = [page(1, [span("答案", 100)]), page(2, [span("下一题", 60)])]
        sources = slice_sources(pages, {"page": 1, "y": 60}, {"page": 2, "y": 60})
        self.assertEqual([1], [source["page"] for source in sources])

    def test_blank_interior_page_retained_and_only_footer_removed(self):
        pages = [page(1, [span("123", 100), span("1", 780)]), page(2, [span("2", 780)]),
                 page(3, [span("答案续文", 100)])]
        sources = slice_sources(pages, {"page": 1, "y": 0}, {"page": 3, "y": 792})
        self.assertEqual(3, len(sources))
        self.assertEqual("123", sources[0]["textSpans"][0]["text"])
        self.assertFalse(sources[1]["textSpans"])

    def test_unknown_or_duplicate_targets_fail_instead_of_page_slicing(self):
        toc = page(1, [span("Java篇", 20), span("问题", 60), span("问题", 100)])
        toc["links"] = [{"rect": [48, y, 540, y + 12], "name": str(y),
                         "target": {"page": 2, "topPdf": 732}} for y in [60, 100]]
        raw = {"pages": [toc, page(2, [span("问题", 60)])], "pageCount": 2}
        _, errors = parse_toc(raw, 1)
        self.assertTrue(any("重复边界" in e for e in errors))
        toc["links"][0]["target"] = None
        _, errors = parse_toc(raw, 1)
        self.assertTrue(any("不合法" in e for e in errors))


class RealPdfRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = read_json(ROOT / "output/pdf-probe/raw.json")
        cls.expected = read_json(ROOT / "docs/fixtures/p0-01/expected.json")
        cls.result = analyze(cls.raw, cls.expected)

    def test_actual_extraction_matches_independent_counts_and_frozen_snapshot(self):
        self.assertEqual([], self.result["errors"])
        self.assertEqual(read_json(ROOT / "docs/fixtures/p0-01/baseline.json"), self.result["summary"])

    def test_hashmap_is_one_card_with_four_followups_and_no_next_title(self):
        c = self.result["cards"][2]
        self.assertEqual([10, 11, 12], [s["page"] for s in c["sources"]])
        self.assertEqual(4, len(c["followups"]))
        self.assertIn("hashCode()", c["text"])
        self.assertNotIn(self.result["cards"][3]["title"], c["text"])

    def test_every_body_span_owned_exactly_once(self):
        original = Counter((p["page"], i) for p in self.raw["pages"][6:]
                           for i, s in enumerate(body_spans(p)))
        actual = Counter()
        for c in self.result["cards"]:
            for source in c["sources"]:
                index = {id(s): i for i, s in enumerate(body_spans(self.raw["pages"][source["page"] - 1]))}
                actual.update((source["page"], index[id(s)]) for s in source["textSpans"])
        self.assertEqual(original, actual)

    def test_image_table_code_blank_and_numeric_risks_have_source_fallback(self):
        cards = {c["id"]: c for c in self.result["cards"]}
        self.assertTrue(cards["q027"]["sources"][0]["images"])
        self.assertEqual(1945, cards["q027"]["sources"][0]["images"][0]["pixelWidth"])
        self.assertIn("WHERE", cards["q059"]["text"])
        self.assertIn("#{}", cards["q083"]["text"])
        self.assertTrue(any(f["kind"] == "GLYPH_MAPPING" for f in cards["q103"]["flags"]))
        self.assertIn("1.0", cards["q103"]["text"])
        self.assertEqual([123, 125, 145, 161, 177, 193, 219], self.result["summary"]["blankPages"])
        for c in cards.values():
            self.assertTrue(c["fallback"]["requiresReview"])
            self.assertTrue(c["sources"])

    def test_wrong_expected_counts_report_difference_without_creating_cards_to_fill_gap(self):
        expected = copy.deepcopy(self.expected)
        expected["questionCount"] += 1
        result = analyze(self.raw, expected)
        self.assertEqual(158, len(result["cards"]))
        self.assertTrue(any("questionCount 差异" in e for e in result["errors"]))


if __name__ == "__main__":
    unittest.main()
