package com.careerpilot.documents;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Turns structured document blocks into DOCX (POI), PDF (PDFBox) and TXT bytes. */
@Component
public class DocumentRenderer {
    public byte[] renderDocx(List<Block> blocks) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Block b : blocks) {
                XWPFParagraph p = doc.createParagraph();
                switch (b.kind()) {
                    case TITLE -> run(p, b.text(), true, 20, false);
                    case SUBTITLE -> run(p, b.text(), false, 10.5, true);
                    case HEADING -> run(p, b.text().toUpperCase(), true, 12, false);
                    case ENTRY -> {
                        run(p, b.text(), true, 10.5, false);
                        if (!b.right().isEmpty()) run(p, "    " + b.right(), false, 10.5, false);
                    }
                    case BULLETS -> {
                        boolean first = true;
                        for (String item : b.items()) {
                            XWPFParagraph bp = first ? p : doc.createParagraph();
                            bp.setIndentationLeft(360);
                            run(bp, "• " + item, false, 10.5, false);
                            first = false;
                        }
                    }
                    default -> run(p, b.text(), false, 10.5, false);
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void run(XWPFParagraph p, String text, boolean bold, double size, boolean italic) {
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(bold);
        r.setItalic(italic);
        r.setFontSize((int) Math.round(size));
        r.setFontFamily("Calibri");
    }

    public byte[] renderPdf(List<Block> blocks) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter w = new PdfWriter(doc);
            for (Block b : blocks) {
                switch (b.kind()) {
                    case TITLE -> w.paragraph(b.text(), true, 18, 4);
                    case SUBTITLE -> w.paragraph(b.text(), false, 10, 4);
                    case HEADING -> { w.gap(6); w.paragraph(b.text(), true, 12, 2); }
                    case ENTRY -> w.paragraph(b.right().isEmpty() ? b.text() : b.text() + "    " + b.right(), true, 10, 1);
                    case BULLETS -> { for (String item : b.items()) w.paragraph("• " + item, false, 10, 1, 12); }
                    default -> w.paragraph(b.text(), false, 10, 6);
                }
            }
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] renderTxt(List<Block> blocks) {
        List<String> lines = new ArrayList<>();
        for (Block b : blocks) {
            switch (b.kind()) {
                case BULLETS -> b.items().forEach(i -> lines.add("  - " + i));
                case HEADING -> { lines.add(""); lines.add(b.text().toUpperCase()); }
                case ENTRY -> lines.add(b.text() + (b.right().isEmpty() ? "" : " (" + b.right() + ")"));
                default -> lines.add(b.text());
            }
        }
        return (String.join("\n", lines).strip() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Minimal top-to-bottom text layout with word wrapping and page breaks. */
    private static final class PdfWriter {
        private static final float MARGIN = 50, WIDTH = PDRectangle.LETTER.getWidth() - 2 * MARGIN;
        private final PDDocument doc;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream stream;
        private float y;

        PdfWriter(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            y = PDRectangle.LETTER.getHeight() - MARGIN;
        }

        void gap(float h) { y -= h; }

        void paragraph(String text, boolean isBold, float size, float after) throws IOException { paragraph(text, isBold, size, after, 0); }

        void paragraph(String text, boolean isBold, float size, float after, float indent) throws IOException {
            PDType1Font font = isBold ? bold : regular;
            float leading = size * 1.3f;
            for (String line : wrap(clean(text), font, size, WIDTH - indent)) {
                if (y - leading < MARGIN) newPage();
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(MARGIN + indent, y - size);
                stream.showText(line);
                stream.endText();
                y -= leading;
            }
            y -= after;
        }

        private static String clean(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : (s == null ? "" : s).toCharArray()) {
                if (c == '•') sb.append('•');
                else if (c == '’' || c == '‘') sb.append('\'');
                else if (c == '“' || c == '”') sb.append('"');
                else if (c == '–' || c == '—') sb.append('-');
                else if (c < 256 && (c >= 32 || c == '\n')) sb.append(c);
                else sb.append(c == '\t' ? ' ' : '?');
            }
            return sb.toString();
        }

        private List<String> wrap(String text, PDType1Font font, float size, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String para : text.split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (String word : para.split(" ")) {
                    String candidate = line.length() == 0 ? word : line + " " + word;
                    if (width(candidate, font, size) > width && line.length() > 0) {
                        lines.add(line.toString());
                        line = new StringBuilder(word);
                    } else line = new StringBuilder(candidate);
                }
                lines.add(line.toString());
            }
            return lines;
        }

        private float width(String s, PDType1Font font, float size) throws IOException {
            try {
                return font.getStringWidth(s) / 1000 * size;
            } catch (IllegalArgumentException e) {
                return s.length() * size * 0.5f;
            }
        }

        void close() throws IOException { stream.close(); }
    }
}
