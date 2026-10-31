package com.interviewflashcards.importing;

import static org.assertj.core.api.Assertions.*;
import static com.interviewflashcards.importing.PdfEvidence.obj;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.*;

class PdfImportParserTest {
    private static final ObjectMapper mapper=new ObjectMapper();
    private static PdfImportParser.Result real;
    private static JsonNode baseline;
    @BeforeAll static void extractRealPdf() throws Exception {
        Path docs=Path.of("../docs");
        try(var doc=Loader.loadPDF(docs.resolve("Java+agent八股(高频版).pdf").toFile())) {
            real=new PdfImportParser().analyze(PdfEvidence.extract(doc, p -> {}));
        }
        baseline=mapper.readTree(docs.resolve("fixtures/p0-01/baseline.json").toFile());
    }
    @Test void allRealCandidatesMatchReviewedTextBoundariesPagesFollowupsAndFlagKinds() {
        assertThat(real.cards()).hasSize(baseline.path("questionCount").asInt());
        assertThat(real.report().get("pageCount")).isEqualTo(222);
        assertThat((JsonNode)mapper.valueToTree(real.report().get("modules"))).isEqualTo(baseline.get("modules"));
        var counts=new LinkedHashMap<String,Integer>();
        for(int i=0;i<real.cards().size();i++) {
            var c=real.cards().get(i); var b=baseline.path("cards").get(i);
            counts.merge(c.chapter(),1,Integer::sum);
            assertThat(c.entry()).isEqualTo(b.path("id").asText());
            assertThat(c.question()).isEqualTo(b.path("title").asText());
            assertThat(c.contentSha256()).as(c.entry()+" full answer hash").isEqualTo(b.path("contentSha256").asText());
            assertThat(c.start().page()).isEqualTo(b.at("/start/page").asInt());
            assertThat(c.start().y()).isCloseTo(b.at("/start/y").asDouble(),within(.001));
            assertThat(c.endExclusive().page()).isEqualTo(b.at("/endExclusive/page").asInt());
            assertThat(c.endExclusive().y()).isCloseTo(b.at("/endExclusive/y").asDouble(),within(.001));
            assertThat((JsonNode)mapper.valueToTree(c.sources().stream().map(s->s.get("page")).toList())).isEqualTo(b.get("sourcePages"));
            assertThat(c.followups()).hasSize(b.path("followupCount").asInt());
            assertThat((JsonNode)mapper.valueToTree(c.flags().stream().map(f->(String)f.get("kind")).distinct().sorted().toList())).isEqualTo(b.get("flagKinds"));
        }
        assertThat((JsonNode)mapper.valueToTree(counts)).isEqualTo(baseline.get("chapters"));
        assertThat((List<?>)real.report().get("missingChapters")).isEmpty();
    }
    @Test void hashmapRetainsCrossPageAnswerAndFourCompleteFollowups() {
        var c=real.cards().stream().filter(card->card.question().contains("HashMap")&&card.question().contains("扩容")).findFirst().orElseThrow();
        assertThat(c.sources().stream().map(s->s.get("page"))).containsExactly(10,11,12);
        assertThat(c.followups()).hasSize(4);
        for(var f:c.followups()) assertThat((String)f.get("text")).startsWith((String)f.get("title")).hasSizeGreaterThan(((String)f.get("title")).length());
    }
    private JsonNode span(String text,double y) { return mapper.valueToTree(obj("text",text,"bbox",List.of(10,y,200,y+12),"glyphIssues",List.of())); }
    private JsonNode synthetic(boolean reversed) {
        var pages=new ArrayList<Object>();
        for(int i=1;i<=8;i++) pages.add(obj("page",i,"width",600,"height",800,"rotation",0,"spans",List.of(),"images",List.of(),"links",List.of()));
        var toc=(Map<String,Object>)pages.getFirst();
        toc.put("spans",List.of(span("Java篇",10),span("First question",40),span("Second question",70)));
        toc.put("links",List.of(obj("rect",List.of(0,40,300,53),"name","a","target",obj("page",7,"topPdf",reversed?600:750)),obj("rect",List.of(0,70,300,83),"name","b","target",obj("page",7,"topPdf",600))));
        ((Map<String,Object>)pages.get(6)).put("spans",List.of(span("First question",reversed?250:50),span("First answer has sufficient text.",100),span("Second question",200),span("Second answer continues to next page.",250)));
        ((Map<String,Object>)pages.get(7)).put("spans",List.of(span("Tail of the complete second answer.",40),span("8",780)));
        return mapper.valueToTree(obj("pages",pages,"pageCount",8));
    }
    @Test void samePageBoundaryIsHalfOpenAndLastAnswerContinuesAcrossPagesWithFooterRemoved() {
        var result=new PdfImportParser().analyze(synthetic(false));
        assertThat(result.cards()).hasSize(2);
        assertThat(result.cards().getFirst().text()).contains("First answer").doesNotContain("Second question");
        assertThat(result.cards().getLast().text()).contains("Second question","Tail of the complete").doesNotEndWith("\n8");
        assertThat(result.report().get("baselineDifference")).isEqualTo(-156);
        assertThat(result.cards().getFirst().endExclusive()).isEqualTo(result.cards().getLast().start());
    }
    @Test void reversedBoundaryAndMissingTitleFailExplicitlyInsteadOfCreatingPartialCandidates() {
        assertThatThrownBy(()->new PdfImportParser().analyze(synthetic(true))).isInstanceOf(PdfImportParser.ParseFailure.class).hasMessageContaining("递增");
        var raw=synthetic(false).deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)raw.at("/pages/6/spans/0")).put("text","Unknown title");
        assertThatThrownBy(()->new PdfImportParser().analyze(raw)).isInstanceOf(PdfImportParser.ParseFailure.class).hasMessageContaining("无法");
    }
    @Test void blankOrScannedPdfHasActionableFailure() {
        var raw=mapper.valueToTree(obj("pageCount",1,"pages",List.of(obj("page",1,"height",800,"spans",List.of(),"images",List.of(),"links",List.of()))));
        assertThatThrownBy(()->new PdfImportParser().analyze(raw)).isInstanceOf(PdfImportParser.ParseFailure.class).hasMessageContaining("扫描件");
    }
}
