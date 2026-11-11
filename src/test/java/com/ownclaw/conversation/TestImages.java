package com.ownclaw.conversation;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;

/** Pictures for the tests of pasted images, made here and never read from anywhere. */
public final class TestImages {

    static {
        // Before anything touches AWT: the suite runs where no display is.
        System.setProperty("java.awt.headless", "true");
    }

    private TestImages() {}

    /** A picture with text on it, as a screenshot is: a documentation address, no one's. */
    public static BufferedImage screenshot(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.drawString("router 192.0.2.1 admin page", 10, 20);
        g.fillRect(width / 3, height / 3, width / 3, height / 3);
        g.dispose();
        return image;
    }

    /** Noise: what a PNG cannot make small. */
    public static BufferedImage noise(int width, int height, long seed) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var r = new Random(seed);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, r.nextInt(0x1000000));
        return image;
    }

    /** {@code image} encoded as {@code format}: png, jpeg, gif. */
    public static byte[] encoded(BufferedImage image, String format) throws Exception {
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) throw new IllegalStateException("no writer for " + format);
        return out.toByteArray();
    }

    /** A PNG screenshot of this size. */
    public static byte[] png(int width, int height) throws Exception {
        return encoded(screenshot(width, height), "png");
    }
}
