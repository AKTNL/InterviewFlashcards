import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Read-only feasibility probe; no production import or content correction. */
public final class PdfProbe {
    static Map<String, Object> obj(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !(args[0].equals("extract") || args[0].equals("render"))) {
            throw new IllegalArgumentException("extract <pdf> <raw.json> | render <pdf> <directory> <1-based-pages-csv>");
        }
        Path input = Path.of(args[1]);
        try (PDDocument doc = Loader.loadPDF(input.toFile())) {
            if (args[0].equals("render")) {
                if (args.length != 4) throw new IllegalArgumentException("Supply pages to render");
                Path directory = Path.of(args[2]);
                Files.createDirectories(directory);
                var renderer = new PDFRenderer(doc);
                for (String value : args[3].split(",")) {
                    int page = Integer.parseInt(value);
                    ImageIO.write(renderer.renderImageWithDPI(page - 1, 110), "png",
                        directory.resolve("page-%03d.png".formatted(page)).toFile());
                }
                return;
            }
            var pages = new ArrayList<Object>();
            var fontNames = new IdentityHashMap<org.apache.pdfbox.cos.COSDictionary, String>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getCropBox();
                var text = new PositionedText(fontNames);
                text.setStartPage(i + 1);
                text.setEndPage(i + 1);
                text.getText(doc);
                var graphics = new Graphics(page);
                graphics.processPage(page);
                var links = new ArrayList<Object>();
                for (var annotation : page.getAnnotations()) {
                    if (!(annotation instanceof PDAnnotationLink link)) continue;
                    var rect = link.getRectangle();
                    var destination = link.getDestination();
                    if (destination == null && link.getAction() instanceof PDActionGoTo go) destination = go.getDestination();
                    String name = destination instanceof PDNamedDestination n ? n.getNamedDestination() : null;
                    if (destination instanceof PDNamedDestination named) destination = doc.getDocumentCatalog().findNamedDestinationPage(named);
                    Object target = null;
                    if (destination instanceof PDPageDestination dest) {
                        int index = dest.retrievePageNumber();
                        // Page object destinations resolve via the actual page tree, never printed labels.
                        if (dest.getPage() != null) index = doc.getPages().indexOf(dest.getPage());
                        target = obj("page", index + 1, "type", dest.getClass().getSimpleName(),
                            "left", dest instanceof PDPageXYZDestination xyz ? xyz.getLeft() : null,
                            "topPdf", dest instanceof PDPageXYZDestination xyz ? xyz.getTop() : null);
                    }
                    links.add(obj("rect", List.of(rect.getLowerLeftX() - box.getLowerLeftX(),
                        box.getUpperRightY() - rect.getUpperRightY(), rect.getUpperRightX() - box.getLowerLeftX(),
                        box.getUpperRightY() - rect.getLowerLeftY()), "name", name, "target", target));
                }
                pages.add(obj("page", i + 1, "width", box.getWidth(), "height", box.getHeight(),
                    "rotation", page.getRotation(), "spans", text.spans, "links", links,
                    "images", graphics.images, "horizontalRules", graphics.horizontal, "verticalRules", graphics.vertical));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestInputStream(Files.newInputStream(input), digest)) { stream.transferTo(OutputStream.nullOutputStream()); }
            var root = obj("schemaVersion", 1, "parserVersion", "pdfbox-3.0.5/probe-v1", "file", input.getFileName().toString(),
                "sha256", HexFormat.of().formatHex(digest.digest()), "bytes", Files.size(input), "pageCount", doc.getNumberOfPages(), "pages", pages);
            Path output = Path.of(args[2]).toAbsolutePath();
            Files.createDirectories(output.getParent());
            Files.writeString(output, json(root) + "\n", StandardCharsets.UTF_8);
            System.out.println("Extracted " + pages.size() + " pages to " + output);
        }
    }

    static class PositionedText extends PDFTextStripper {
        final List<Object> spans = new ArrayList<>();
        final Map<org.apache.pdfbox.cos.COSDictionary, String> fontNames;
        PositionedText(Map<org.apache.pdfbox.cos.COSDictionary, String> fontNames) throws IOException {
            this.fontNames = fontNames;
            setSortByPosition(true); setSuppressDuplicateOverlappingText(true);
        }
        @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions.isEmpty()) return;
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = 0, y1 = 0, size = 0;
            var fonts = new TreeSet<String>();
            var faults = new ArrayList<Object>();
            for (TextPosition pos : positions) {
                // Type3 ink metrics can be much smaller than visible glyphs. Use the
                // transformed em height as a conservative region, not an exact ink box.
                x0 = Math.min(x0, pos.getXDirAdj()); y0 = Math.min(y0, pos.getYDirAdj() - Math.max(pos.getHeightDir(), pos.getYScale()));
                x1 = Math.max(x1, pos.getXDirAdj() + pos.getWidthDirAdj()); y1 = Math.max(y1, pos.getYDirAdj());
                String font = fontNames.computeIfAbsent(pos.getFont().getCOSObject(), key ->
                    Objects.toString(pos.getFont().getName(), pos.getFont().getSubType()) + "-" + (fontNames.size() + 1));
                size = Math.max(size, pos.getYScale()); fonts.add(font);
                for (int code : pos.getCharacterCodes()) {
                    String unicode = pos.getFont().toUnicode(code);
                    if (unicode == null || unicode.isBlank() || unicode.codePoints().anyMatch(c -> c == 0xfffd || c == 0xfeff || c == 0 || (c >= 0xe000 && c <= 0xf8ff))) {
                        // Ordinary space is valid; empty, BOM and private glyphs are review evidence.
                        if (!" ".equals(unicode)) faults.add(obj("code", code, "unicode", unicode, "font", font, "x", pos.getXDirAdj(), "y", pos.getYDirAdj()));
                    }
                }
            }
            spans.add(obj("text", text, "bbox", List.of(x0, y0, x1, y1), "fontSize", size, "fonts", fonts, "glyphIssues", faults));
        }
    }

    static class Graphics extends PDFGraphicsStreamEngine {
        final List<Object> images = new ArrayList<>();
        final GeneralPath path = new GeneralPath();
        int horizontal, vertical;
        Graphics(PDPage page) { super(page); }
        @Override protected void processType3Stream(org.apache.pdfbox.pdmodel.font.PDType3CharProc glyph, org.apache.pdfbox.util.Matrix matrix) {
            // Glyph outlines are text, not table borders or document illustrations.
        }
        @Override public void drawImage(PDImage image) {
            var matrix = getGraphicsState().getCurrentTransformationMatrix();
            var corners = List.of(matrix.transformPoint(0, 0), matrix.transformPoint(0, 1), matrix.transformPoint(1, 0), matrix.transformPoint(1, 1));
            double x0 = corners.stream().mapToDouble(p -> p.x).min().orElseThrow(), x1 = corners.stream().mapToDouble(p -> p.x).max().orElseThrow();
            double y0 = corners.stream().mapToDouble(p -> p.y).min().orElseThrow(), y1 = corners.stream().mapToDouble(p -> p.y).max().orElseThrow();
            var box = getPage().getCropBox();
            images.add(obj("bbox", List.of(x0 - box.getLowerLeftX(), box.getUpperRightY() - y1, x1 - box.getLowerLeftX(), box.getUpperRightY() - y0),
                "pixelWidth", image.getWidth(), "pixelHeight", image.getHeight()));
        }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { horizontal += 2; vertical += 2; path.moveTo(p0.getX(), p0.getY()); path.closePath(); }
        @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
        @Override public void lineTo(float x, float y) {
            Point2D point = path.getCurrentPoint();
            if (point != null) {
                if (Math.abs(point.getY() - y) < .5 && Math.abs(point.getX() - x) > 20) horizontal++;
                if (Math.abs(point.getX() - x) < .5 && Math.abs(point.getY() - y) > 10) vertical++;
            }
            path.lineTo(x, y);
        }
        @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { path.curveTo(x1, y1, x2, y2, x3, y3); }
        @Override public void closePath() { path.closePath(); }
        @Override public void endPath() { path.reset(); }
        @Override public void strokePath() { path.reset(); }
        @Override public void fillPath(int windingRule) { path.reset(); }
        @Override public void fillAndStrokePath(int windingRule) { path.reset(); }
        @Override public void clip(int windingRule) { }
        @Override public void shadingFill(COSName name) { }
    }

    // Small JSON encoder for extraction evidence; no extra runtime dependencies.
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var parts = new ArrayList<String>();
            map.forEach((key, item) -> parts.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Iterable<?> items) {
            var parts = new ArrayList<String>(); for (Object item : items) parts.add(json(item));
            return "[" + String.join(",", parts) + "]";
        }
        StringBuilder escaped = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\""); case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n"); case '\r' -> escaped.append("\\r"); case '\t' -> escaped.append("\\t");
                default -> { if (c < 32) escaped.append("\\u%04x".formatted((int) c)); else escaped.append(c); }
            }
        }
        return escaped.append('"').toString();
    }
}
