package com.careerpilot.documents;

import java.util.List;

/** Neutral layout block that {@link DocumentRenderer} turns into DOCX, PDF or TXT. */
public record Block(Kind kind, String text, String right, List<String> items) {
    public enum Kind { TITLE, SUBTITLE, HEADING, ENTRY, BULLETS, PARAGRAPH, TEXT }

    public static Block of(Kind kind, String text) { return new Block(kind, text, "", List.of()); }
    public static Block entry(String text, String right) { return new Block(Kind.ENTRY, text, right == null ? "" : right, List.of()); }
    public static Block bullets(List<String> items) { return new Block(Kind.BULLETS, "", "", items); }
}
