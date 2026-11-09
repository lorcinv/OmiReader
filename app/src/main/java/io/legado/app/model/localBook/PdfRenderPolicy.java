package io.legado.app.model.localBook;

/** Limits a PDF raster before allocating its bitmap. No Android dependencies. */
public final class PdfRenderPolicy {
    public static final long MAX_PIXELS = 4L * 1024 * 1024;
    public static final int MAX_SIDE = 8192;
    public static final int MAX_REQUESTED_WIDTH = 2560;

    private PdfRenderPolicy() { }

    public static int readingWidth(int screenWidth) {
        // Supersampling improves small type and the existing image zoom view. The
        // pixel and long-side limits below still apply to unusual page shapes.
        return (int) Math.max(720L, Math.min(MAX_REQUESTED_WIDTH,
                Math.max(1L, screenWidth) * 3L / 2L));
    }

    public static RasterSize dimensions(int pageWidth, int pageHeight, int requestedWidth) {
        if (pageWidth <= 0 || pageHeight <= 0 || requestedWidth <= 0) {
            throw new IllegalArgumentException("PDF page dimensions must be positive");
        }
        double scale = Math.min((double) Math.min(requestedWidth, MAX_REQUESTED_WIDTH) / pageWidth,
                Math.sqrt(MAX_PIXELS / ((double) pageWidth * pageHeight)));
        scale = Math.min(scale, (double) MAX_SIDE / Math.max(pageWidth, pageHeight));
        int width = Math.max(1, (int) Math.floor(pageWidth * scale));
        int height = Math.max(1, (int) Math.floor(pageHeight * scale));
        return new RasterSize(width, height);
    }

    public static String pageTitle(int segmentIndex, int pageCount, int segmentSize) {
        if (segmentIndex < 0 || pageCount <= 0 || segmentSize <= 0) {
            throw new IllegalArgumentException("Invalid PDF page range");
        }
        long start = (long) segmentIndex * segmentSize + 1;
        if (start > pageCount) throw new IllegalArgumentException("PDF page range is outside document");
        long end = Math.min(start + segmentSize - 1, pageCount);
        return start == end ? "第 " + start + " 页" : "第 " + start + "—" + end + " 页";
    }

    public static final class RasterSize {
        public final int width;
        public final int height;

        private RasterSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
