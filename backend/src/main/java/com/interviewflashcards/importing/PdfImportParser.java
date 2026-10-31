package com.interviewflashcards.importing;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import static com.interviewflashcards.importing.PdfEvidence.obj;

/** Current linked-TOC format only. Counts validate output; they never generate candidates. */
public final class PdfImportParser {
    public static final String VERSION = "pdfbox-3.0.5/import-v1";
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("Java篇", "Java 基础"), Map.entry("JVM篇", "JVM"), Map.entry("JUC篇", "JUC / 并发编程"),
        Map.entry("MySQL篇", "MySQL"), Map.entry("Reids篇", "Redis"), Map.entry("Spring篇", "Spring"),
        Map.entry("Mybatis篇", "MyBatis"), Map.entry("消息队列篇", "消息队列"), Map.entry("计算机网络篇", "计算机网络"),
        Map.entry("操作系统篇", "操作系统"), Map.entry("agent篇", "Agent"), Map.entry("RAG篇", "RAG"), Map.entry("LLM篇", "LLM"));
    private static final Map<String, Integer> EXPECTED = Map.ofEntries(
        Map.entry("Java 基础",17), Map.entry("JVM",6), Map.entry("JUC / 并发编程",20), Map.entry("MySQL",17),
        Map.entry("Redis",17), Map.entry("Spring",5), Map.entry("MyBatis",3), Map.entry("消息队列",8),
        Map.entry("计算机网络",15), Map.entry("操作系统",8), Map.entry("Agent",18), Map.entry("RAG",16), Map.entry("LLM",8));
    public record Position(int page, double y) implements Comparable<Position> {
        public int compareTo(Position other) { int p = Integer.compare(page, other.page); return p == 0 ? Double.compare(y, other.y) : p; }
    }
    public record Candidate(String entry, String question, String chapter, String originalChapter, String module,
        Position start, Position endExclusive, List<Map<String,Object>> sources, String text, String contentSha256,
        List<Map<String,Object>> followups, List<Map<String,Object>> flags, Map<String,Object> metadata) {}
    public record Result(List<Candidate> cards, Map<String,Object> report) {}
    public static final class ParseFailure extends RuntimeException {
        public final String code;
        public ParseFailure(String code, String message) { super(message); this.code = code; }
    }
    private record Entry(String id, String title, String chapter, String rawChapter, String module,
                         Position start, JsonNode link, List<JsonNode> glyphs, int tocPage) {}
    private static String normalize(String s) { return s.replaceAll("[\\s\\p{Z}\\p{Cf}]", ""); }
    private static double n(JsonNode node, int i) { return node.get(i).asDouble(); }
    private static boolean overlaps(JsonNode a, JsonNode b) {
        return Math.min(n(a,2), n(b,2)) > Math.max(n(a,0), n(b,0)) && Math.min(n(a,3),n(b,3)) > Math.max(n(a,1),n(b,1));
    }
    private static List<JsonNode> body(JsonNode page) {
        var spans = new ArrayList<JsonNode>();
        for (var span : page.path("spans"))
            if (!(n(span.get("bbox"),1) > page.path("height").asDouble()-35 && span.path("text").asText().strip().equals(page.path("page").asText()))) spans.add(span);
        return spans;
    }
    private static Position title(JsonNode page, String label, JsonNode target) {
        var spans = body(page);
        spans.sort(Comparator.<JsonNode>comparingDouble(s -> Math.rint(n(s.get("bbox"),1)/3)).thenComparingDouble(s -> n(s.get("bbox"),0)));
        String wanted = normalize(label);
        var matches = new ArrayList<Double>();
        for (int i=0; i<spans.size(); i++) {
            String joined = "";
            for (int j=i; j<Math.min(i+6,spans.size()); j++) {
                joined += normalize(spans.get(j).path("text").asText());
                if (!wanted.startsWith(joined)) break;
                if (joined.equals(wanted)) { matches.add(n(spans.get(i).get("bbox"),1)); break; }
            }
        }
        if (matches.isEmpty()) throw new ParseFailure("BOUNDARY_UNCERTAIN", "目录标题无法在正文定位，请核对来源或重新选择当前支持的 PDF。");
        double anchor = target.path("topPdf").isNumber() && target.path("topPdf").asDouble() >= 0 ? page.path("height").asDouble()-target.path("topPdf").asDouble() : 0;
        return new Position(page.path("page").asInt(), matches.stream().min(Comparator.comparingDouble(y -> Math.abs(y-anchor))).orElseThrow());
    }
    public Result analyze(JsonNode raw) {
        JsonNode pages = raw.path("pages");
        long chars = 0; for (var page : pages) for (var span : body(page)) chars += span.path("text").asText().strip().length();
        if (chars < 20) throw new ParseFailure("INSUFFICIENT_TEXT", "PDF 可提取文本不足，扫描件尚不支持。请重新选择包含文本和链接目录的 PDF。");
        var entries = new ArrayList<Entry>(); String chapter=null, original=null;
        for (int p=0; p<Math.min(6,pages.size()); p++) {
            var page = pages.get(p);
            record Event(double y, int kind, JsonNode node) {}
            var events = new ArrayList<Event>();
            for (var span : page.path("spans")) if (normalize(span.path("text").asText()).endsWith("篇")) events.add(new Event(n(span.get("bbox"),1),0,span));
            for (var link : page.path("links")) events.add(new Event(n(link.get("rect"),1),1,link));
            events.sort(Comparator.comparingDouble(Event::y).thenComparingInt(Event::kind));
            for (var event : events) {
                if (event.kind()==0) {
                    original=normalize(event.node().path("text").asText()); chapter=ALIASES.get(original);
                    if (chapter==null) throw new ParseFailure("UNSUPPORTED_TOC", "目录章节样式尚未适配，请选择当前支持的资料。");
                    continue;
                }
                var link=event.node(); var labels=new ArrayList<String>(); var glyphs=new ArrayList<JsonNode>();
                for (var span : page.path("spans")) if (overlaps(span.get("bbox"),link.get("rect"))) {
                    labels.add(span.path("text").asText().strip()); span.path("glyphIssues").forEach(glyphs::add);
                }
                String label=String.join(" ",labels); var target=link.path("target"); int dest=target.path("page").asInt();
                if (chapter==null || label.isBlank() || dest<=6 || dest>pages.size()) throw new ParseFailure("UNSUPPORTED_TOC", "目录链接缺少有效的正文目标，请核对或重新选择文件。");
                if (pages.get(dest-1).path("rotation").asInt()!=0) throw new ParseFailure("UNSUPPORTED_ROTATION", "正文包含尚未适配的旋转页。");
                Position start=title(pages.get(dest-1),label,target);
                if (!entries.isEmpty() && entries.getLast().start().compareTo(start)>=0) throw new ParseFailure("BOUNDARY_UNCERTAIN", "目录目标重复或未按正文递增，请人工核对来源。");
                entries.add(new Entry("q%03d".formatted(entries.size()+1),label,chapter,original,
                    Set.of("Agent","RAG","LLM").contains(chapter)?"AI 扩展":"Java 后端基础",start,link,glyphs,p+1));
            }
        }
        if (entries.isEmpty()) throw new ParseFailure("UNSUPPORTED_TOC", "未找到支持的链接目录，请重新选择当前支持的 PDF。");
        var cards=new ArrayList<Candidate>(); var counts=new LinkedHashMap<String,Integer>(); var modules=new LinkedHashMap<String,Integer>();
        for (int i=0;i<entries.size();i++) {
            Entry e=entries.get(i); Position end=i+1<entries.size()?entries.get(i+1).start():new Position(pages.size(),pages.get(pages.size()-1).path("height").asDouble());
            cards.add(make(e,end,pages)); counts.merge(e.chapter(),1,Integer::sum); modules.merge(e.module(),1,Integer::sum);
        }
        var chapters=new ArrayList<Object>();
        counts.forEach((name,count)->chapters.add(obj("name",name,"expectedCount",EXPECTED.getOrDefault(name,0),"tocCount",count,"candidateCount",count,
            "recognitionDifference",0,"baselineDifference",count-EXPECTED.getOrDefault(name,0))));
        var missing=new ArrayList<String>(); EXPECTED.keySet().stream().filter(k -> !counts.containsKey(k)).sorted().forEach(missing::add);
        return new Result(cards,obj("pageCount",pages.size(),"expectedPageCount",222,"tocCount",entries.size(),"candidateCount",cards.size(),
            "expectedCount",158,"recognitionDifference",cards.size()-entries.size(),"baselineDifference",cards.size()-158,
            "chapters",chapters,"missingChapters",missing,"modules",modules,"parserVersion",VERSION,
            "preservation","完整文字、坐标、图片区域及鉴权 PDF/页面原图；代码表格语义需核对，不补全或摘要化"));
    }
    private Candidate make(Entry e, Position end, JsonNode pages) {
        var sources=new ArrayList<Map<String,Object>>(); var flags=new ArrayList<Map<String,Object>>(); var followups=new ArrayList<Map<String,Object>>();
        var lines=new ArrayList<String>();
        for (int p=e.start().page();p<=end.page();p++) {
            var page=pages.get(p-1); double low=p==e.start().page()?e.start().y():0, high=p==end.page()?end.y():page.path("height").asDouble();
            if (low>=high) continue;
            var spans=new ArrayList<JsonNode>(); var images=new ArrayList<JsonNode>();
            var bbox=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(List.of(0,low,page.path("width").asDouble(),high));
            for (var span:body(page)) if (n(span.get("bbox"),1)>=low && n(span.get("bbox"),1)<high) spans.add(span);
            for (var image:page.path("images")) if (overlaps(image.get("bbox"),bbox)) images.add(image);
            if (p==end.page() && spans.isEmpty() && images.isEmpty()) continue;
            sources.add(obj("page",p,"bbox",bbox,"textSpans",spans,"images",images));
            for (var span:spans) {
                String text=span.path("text").asText(); lines.add(text);
                if (Pattern.compile("^追加\\s*[:：]").matcher(text.strip()).find()) followups.add(obj("page",p,"bbox",span.get("bbox"),"title",text.strip()));
                if (!span.path("glyphIssues").isEmpty()) flags.add(obj("kind","GLYPH_MAPPING","page",p,"bbox",span.get("bbox"),"reason","字体映射异常，可能缺字或数字，请核对原文；不猜测补全", "evidence",span.get("glyphIssues")));
                if (text.codePoints().anyMatch(c -> c==0xfeff || c==0xfffd || c<32 && c!='\n' && c!='\t' && c!='\r')) flags.add(obj("kind","SPECIAL_CHARACTER","page",p,"bbox",span.get("bbox"),"reason","提取文字含特殊或控制字符，保留原值待核对"));
            }
            if (!images.isEmpty()) flags.add(obj("kind","IMAGE_REQUIRES_SOURCE","page",p,"reason","图片保留于来源页面原图，需对照阅读","evidence",images));
            if (spans.isEmpty() && images.isEmpty()) flags.add(obj("kind","EMPTY_PAGE","page",p,"reason","来源范围含空白页，未生成替代文字"));
        }
        if (!e.glyphs().isEmpty()) flags.add(obj("kind","TOC_GLYPH_MAPPING","page",e.tocPage(),"reason","目录字体映射存在风险，请核对标题","evidence",e.glyphs()));
        flags.add(obj("kind","LAYOUT_REQUIRES_SOURCE","reason","段落、代码/表格语义未自动重建；保留全部文字位置和来源页段，逐章核对"));
        // Keep each follow-up's complete text, in addition to its position in the unabridged answer.
        var completeFollowups=new ArrayList<Map<String,Object>>();
        Map<String,Object> current=null; var followupLines=new ArrayList<String>(); int followupIndex=0;
        for(String line:lines) {
            if(Pattern.compile("^追加\\s*[:：]").matcher(line.strip()).find()) {
                if(current!=null) { current.put("text",String.join("\n",followupLines)); completeFollowups.add(current); }
                current=new LinkedHashMap<>(followups.get(followupIndex++)); followupLines=new ArrayList<>();
            }
            if(current!=null) followupLines.add(line);
        }
        if(current!=null) { current.put("text",String.join("\n",followupLines)); completeFollowups.add(current); }
        String text=String.join("\n",lines);
        String hash; try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); }
        var metadata=obj("tocPage",e.tocPage(),"tocRect",e.link().get("rect"),"destinationName",e.link().get("name"),"target",e.link().get("target"),
            "start",e.start(),"endExclusive",end,"contentSha256",hash,"parserVersion",VERSION);
        return new Candidate(e.id(),e.title(),e.chapter(),e.rawChapter(),e.module(),e.start(),end,sources,text,hash,completeFollowups,flags,metadata);
    }
}
