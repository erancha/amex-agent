package com.erancha.amexagent.card.infra;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Reads a PDF into lines of positioned text chunks, top to bottom on each page. A chunk is a run
 * of words PDFBox found without a wide gap, in logical (reading) order, so right-to-left Hebrew
 * reads correctly; its horizontal extent covers only the non-blank glyphs, so padding spaces never
 * stretch a chunk into a neighbouring column.
 */
final class PdfTextLines {
  /** One run of text; left and right are page x coordinates in points, left smaller. */
  record Chunk(String text, float left, float right) {}

  /** One visual line; page counts from 1. */
  record Line(int page, List<Chunk> chunks) {
    String text() {
      return String.join(" ", chunks.stream().map(Chunk::text).toList());
    }
  }

  private PdfTextLines() {}

  static List<Line> read(Path file) {
    try (PDDocument doc = Loader.loadPDF(file.toFile())) {
      Collector collector = new Collector();
      collector.setSortByPosition(true);
      collector.getText(doc);
      return List.copyOf(collector.lines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** PDFBox reports text through these callbacks; nothing is written to the returned string. */
  private static final class Collector extends PDFTextStripper {
    private final List<Line> lines = new ArrayList<>();
    private final List<Chunk> current = new ArrayList<>();

    private Collector() throws IOException {}

    @Override
    protected void startPage(PDPage page) throws IOException {
      super.startPage(page);
      current.clear();
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
      float left = Float.MAX_VALUE;
      float right = -Float.MAX_VALUE;
      for (TextPosition p : positions) {
        if (!p.getUnicode().isBlank()) {
          left = Math.min(left, p.getXDirAdj());
          right = Math.max(right, p.getXDirAdj() + p.getWidthDirAdj());
        }
      }
      if (left <= right) {
        current.add(new Chunk(text.strip(), left, right));
      }
    }

    @Override
    protected void writeLineSeparator() {
      endLine();
    }

    @Override
    protected void writePageEnd() {
      endLine();
    }

    @Override
    protected void writeWordSeparator() {}

    @Override
    protected void writeParagraphSeparator() {}

    private void endLine() {
      if (!current.isEmpty()) {
        lines.add(new Line(getCurrentPageNo(), List.copyOf(current)));
        current.clear();
      }
    }
  }
}
