import io.legado.app.model.localBook.PdfRenderPolicy;
import java.util.Random;

/** Host checks for allocation bounds, scaling and preserved PDF chapter ranges. */
public final class PdfRenderPolicyChecks {
    public static void main(String[] args) {
        PdfRenderPolicy.RasterSize a4 = PdfRenderPolicy.dimensions(595, 842, 1620);
        check(a4.width == 1620 && a4.height == 2292, "A4 reading resolution");
        PdfRenderPolicy.RasterSize landscape = PdfRenderPolicy.dimensions(842, 595, 1620);
        check(landscape.width == 1620 && landscape.height == 1144, "landscape is not stretched");
        PdfRenderPolicy.RasterSize zoom = PdfRenderPolicy.dimensions(595, 842, 2560);
        check((long) zoom.width * zoom.height <= PdfRenderPolicy.MAX_PIXELS, "high-res allocation cap");
        PdfRenderPolicy.RasterSize longPage = PdfRenderPolicy.dimensions(1, Integer.MAX_VALUE, 2560);
        check(longPage.width == 1 && longPage.height <= PdfRenderPolicy.MAX_SIDE, "extreme tall PDF");
        PdfRenderPolicy.RasterSize widePage = PdfRenderPolicy.dimensions(Integer.MAX_VALUE, 1, 2560);
        check(widePage.height == 1 && widePage.width <= 2560, "extreme wide PDF");
        check(PdfRenderPolicy.readingWidth(Integer.MAX_VALUE) == 2560, "screen arithmetic cannot overflow");
        check(PdfRenderPolicy.readingWidth(1080) == 1620, "phone sampling");
        check(PdfRenderPolicy.readingWidth(0) == 720, "unmeasured window fallback");
        check(PdfRenderPolicy.pageTitle(0, 25, 10).equals("第 1—10 页"), "first segment");
        check(PdfRenderPolicy.pageTitle(2, 25, 10).equals("第 21—25 页"), "last partial segment");
        check(PdfRenderPolicy.pageTitle(2, 21, 10).equals("第 21 页"), "last single page");
        rejects(() -> PdfRenderPolicy.dimensions(0, 1, 100));
        rejects(() -> PdfRenderPolicy.dimensions(1, -1, 100));
        rejects(() -> PdfRenderPolicy.dimensions(1, 1, 0));
        rejects(() -> PdfRenderPolicy.pageTitle(Integer.MAX_VALUE, 25, 10));
        rejects(() -> PdfRenderPolicy.pageTitle(-1, 25, 10));
        Random random = new Random(20261108L);
        for (int index = 0; index < 10000; index++) {
            int w = 1 + random.nextInt(Integer.MAX_VALUE - 1);
            int h = 1 + random.nextInt(Integer.MAX_VALUE - 1);
            int requested = 1 + random.nextInt(Integer.MAX_VALUE - 1);
            PdfRenderPolicy.RasterSize size = PdfRenderPolicy.dimensions(w, h, requested);
            check(size.width > 0 && size.height > 0, "positive raster");
            check(size.width <= Math.min(requested, PdfRenderPolicy.MAX_REQUESTED_WIDTH), "width bound");
            check(size.width <= PdfRenderPolicy.MAX_SIDE && size.height <= PdfRenderPolicy.MAX_SIDE, "side bound");
            check((long) size.width * size.height <= PdfRenderPolicy.MAX_PIXELS, "pixel budget");
            // Check the aspect ratio to within the one pixel rounding envelope.
            double ratio = (double) w / h;
            check(size.width + 1.01 >= (size.height - 1) * ratio
                    && size.width - 1 <= (size.height + 1.01) * ratio, "aspect ratio");
        }
        System.out.println("PDF policy: allocation and page-range checks passed (10,000 generated page shapes).");
    }

    private static void rejects(Runnable test) {
        try { test.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid dimensions/range was accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
