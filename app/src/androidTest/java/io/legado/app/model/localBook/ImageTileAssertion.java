package io.legado.app.model.localBook;

import java.util.function.IntBinaryOperator;

/** Detects the original 144 x 72 fixture as two uniformly coloured squares after scaling.
 *  Its two-pixel interpolation seam is part of normal bitmap rendering, not missing pixels. */
public final class ImageTileAssertion {
    private static final int BLUE = 0xff4b669b;
    private static final int SAND = 0xffe6dfd5;

    private ImageTileAssertion() { }

    public static boolean containsOriginalTiles(int width, int height, IntBinaryOperator pixel) {
        for (int y = 0; y < height; y += 4) {
            int x = 0;
            while (x < width) {
                if (pixel.applyAsInt(x, y) != BLUE) { x++; continue; }
                int blueStart = x;
                while (x < width && pixel.applyAsInt(x, y) == BLUE) x++;
                int blueEnd = x;
                int blueWidth = blueEnd - blueStart;
                if (blueWidth < 40) continue;

                // A source-pixel boundary can span ceil(scale) screen pixels. Only colours
                // on the BLUE -> SAND interpolation line are accepted in this narrow seam.
                int maximumSeam = Math.min(8, Math.max(2, (blueWidth + 71) / 72 + 1));
                int seamStart = x;
                while (x < width && x - seamStart < maximumSeam && isInterpolatedEdge(pixel.applyAsInt(x, y))) x++;
                int sandStart = x;
                while (x < width && pixel.applyAsInt(x, y) == SAND) x++;
                int sandEnd = x;
                int sandWidth = sandEnd - sandStart;
                if (sandWidth < 40 || Math.abs(blueWidth - sandWidth) > maximumSeam) continue;

                int blueCenter = (blueStart + blueEnd) / 2;
                int sandCenter = (sandStart + sandEnd) / 2;
                int top = y;
                int bottom = y + 1;
                while (top > 0 && samePair(pixel, blueCenter, sandCenter, top - 1)) top--;
                while (bottom < height && samePair(pixel, blueCenter, sandCenter, bottom)) bottom++;
                int tileHeight = bottom - top;
                if (tileHeight < 40 || Math.abs(tileHeight - blueWidth) > maximumSeam + 2 ||
                        Math.abs(tileHeight - sandWidth) > maximumSeam + 2) continue;

                // Two colours anywhere on screen are insufficient: both complete rectangular
                // interiors must have the expected pixels, with the fixture's square shape.
                if (uniform(pixel, blueStart, blueEnd, top, bottom, BLUE) &&
                        uniform(pixel, sandStart, sandEnd, top, bottom, SAND)) return true;
            }
        }
        return false;
    }

    private static boolean samePair(IntBinaryOperator pixel, int left, int right, int y) {
        return pixel.applyAsInt(left, y) == BLUE && pixel.applyAsInt(right, y) == SAND;
    }

    private static boolean uniform(IntBinaryOperator pixel, int left, int right, int top, int bottom, int expected) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) if (pixel.applyAsInt(x, y) != expected) return false;
        }
        return true;
    }

    private static boolean isInterpolatedEdge(int color) {
        if ((color >>> 24) != 255 || color == BLUE || color == SAND) return false;
        int red = (color >> 16) & 255;
        int green = (color >> 8) & 255;
        int blue = color & 255;
        double fraction = (red - 75) / 155.0;
        return fraction > 0 && fraction < 1 && Math.abs(green - (102 + 121 * fraction)) <= 2 &&
                Math.abs(blue - (155 + 58 * fraction)) <= 2;
    }
}
