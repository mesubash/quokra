package io.github.mesubash.quokra.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.knowledge.KnowledgeChunker.Chunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerTests {

    @Test
    void headingsStartPartsAndAreKept() {
        List<Chunk> chunks = KnowledgeChunker.split("""
            Intro line.

            # Shipping
            We ship in two days.

            ## Returns
            Send it back within 30 days.

            # Payments
            Cards and bank transfer.
            """);

        assertThat(chunks).containsExactly(
            new Chunk(null, "Intro line."),
            new Chunk("Shipping", "We ship in two days."),
            new Chunk("Shipping › Returns", "Send it back within 30 days."),
            new Chunk("Payments", "Cards and bank transfer."));
    }

    @Test
    void shortParagraphsArePackedTogether() {
        List<Chunk> chunks = KnowledgeChunker.split("One.\n\nTwo.\r\n\r\nThree.");
        assertThat(chunks).containsExactly(new Chunk(null, "One.\n\nTwo.\n\nThree."));
    }

    @Test
    void partsStayUnderTheLimit() {
        String paragraph = "word ".repeat(150).strip();   // 749 characters
        List<Chunk> chunks = KnowledgeChunker.split(paragraph + "\n\n" + paragraph + "\n\n" + paragraph);

        assertThat(chunks).hasSize(3).allSatisfy(c -> assertThat(c.body()).hasSizeLessThanOrEqualTo(KnowledgeChunker.MAX));
    }

    @Test
    void aLongParagraphIsCutAtSpacesWithOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("w").append(i).append(' ');
        }
        List<Chunk> chunks = KnowledgeChunker.split(text.toString());

        assertThat(chunks).hasSizeGreaterThan(1)
            .allSatisfy(c -> assertThat(c.body()).hasSizeLessThanOrEqualTo(KnowledgeChunker.MAX));
        // Nothing is lost, and each piece repeats the end of the one before.
        assertThat(chunks.getLast().body()).endsWith("w399");
        String firstEnd = chunks.get(0).body().substring(chunks.get(0).body().lastIndexOf(' ') + 1);
        assertThat(chunks.get(1).body()).contains(firstEnd);
        assertThat(chunks.get(0).body()).doesNotEndWith(" ").doesNotContain("w39 9");
    }

    @Test
    void hashesInCodeBlocksArentHeadings() {
        List<Chunk> chunks = KnowledgeChunker.split("""
            # Setup
            ```
            # not a heading

            still code
            ```
            """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().heading()).isEqualTo("Setup");
        assertThat(chunks.getFirst().body()).contains("# not a heading").contains("still code");
    }

    @Test
    void emptyOrHeadingOnlyTextHasNoParts() {
        assertThat(KnowledgeChunker.split("")).isEmpty();
        assertThat(KnowledgeChunker.split("# Only a heading\n\n## And another")).isEmpty();
    }

    @Test
    void textWithoutSpacesIsStillCut() {
        List<Chunk> chunks = KnowledgeChunker.split("x".repeat(2500));
        assertThat(chunks).hasSize(3);
        assertThat(String.join("", chunks.stream().map(Chunk::body).toList())).hasSize(2500);
    }
}
