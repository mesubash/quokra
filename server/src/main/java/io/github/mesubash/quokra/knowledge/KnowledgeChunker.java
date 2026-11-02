package io.github.mesubash.quokra.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Splits a text or Markdown document into parts small enough to search and to quote in an answer.
// Markdown headings start a new part and are kept as its heading; paragraphs are packed together up to
// MAX characters, and a paragraph longer than that is cut at word boundaries, repeating a little of the
// previous piece so a sentence on the cut isn't lost.
public final class KnowledgeChunker {

    public static final int MAX = 1000;
    static final int OVERLAP = 150;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    public record Chunk(String heading, String body) {}

    private KnowledgeChunker() {}

    public static List<Chunk> split(String text) {
        List<Chunk> chunks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder part = new StringBuilder();
        boolean code = false;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                code = !code;
            }
            Matcher heading = code ? null : HEADING.matcher(line);
            if (heading != null && heading.matches()) {
                add(part, paragraph);
                flush(chunks, headings, part);
                int level = heading.group(1).length();
                while (headings.size() >= level) {
                    headings.removeLast();
                }
                while (headings.size() < level - 1) {
                    headings.add("");
                }
                headings.add(heading.group(2));
            } else if (line.isBlank() && !code) {
                add(part, paragraph);
                if (part.length() >= MAX) {
                    flush(chunks, headings, part);
                }
            } else {
                paragraph.append(paragraph.isEmpty() ? "" : "\n").append(line);
            }
            // A part that would grow past MAX goes out first.
            if (!paragraph.isEmpty() && !part.isEmpty() && part.length() + paragraph.length() + 2 > MAX) {
                flush(chunks, headings, part);
            }
        }
        add(part, paragraph);
        flush(chunks, headings, part);
        return chunks;
    }

    private static void add(StringBuilder part, StringBuilder paragraph) {
        String text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (!text.isEmpty()) {
            part.append(part.isEmpty() ? "" : "\n\n").append(text);
        }
    }

    private static void flush(List<Chunk> chunks, List<String> headings, StringBuilder part) {
        String text = part.toString().strip();
        part.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        String heading = String.join(" › ", headings.stream().filter(h -> !h.isBlank()).toList());
        for (String piece : cut(text)) {
            chunks.add(new Chunk(heading.isEmpty() ? null : heading, piece));
        }
    }

    // Pieces of at most MAX characters, cut at a space where there is one.
    static List<String> cut(String text) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (text.length() - start > MAX) {
            int end = text.lastIndexOf(' ', start + MAX);
            if (end <= start + OVERLAP) {
                end = start + MAX;
            }
            pieces.add(text.substring(start, end).strip());
            int next = text.indexOf(' ', end - OVERLAP);
            start = next < 0 || next >= end ? end : next + 1;
        }
        pieces.add(text.substring(start).strip());
        return pieces;
    }
}
