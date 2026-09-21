package io.github.seongeun308.safecheck;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/**
 * 테스트용 이미지를 만든다.
 *
 * <p>단색 이미지는 JPEG가 지나치게 압축해 실제 사진과 특성이 크게 달라진다.
 * 8px 블록 단위로 색을 섞어 압축률을 현실적인 수준으로 맞춘다.
 *
 * <p>크기가 같으면 항상 같은 바이트를 낸다. 해시를 비교하는 테스트가
 * 흔들리지 않도록 난수 시드를 크기에서 유도한다.
 */
public final class TestImages {

    private static final int BLOCK = 8;

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        return encode(noise(width, height, false), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encode(noise(width, height, false), "png");
    }

    /** 알파 채널이 있는 PNG. JPEG 변환 시 배경 처리를 확인할 때 쓴다. */
    public static byte[] transparentPng(int width, int height) {
        return encode(noise(width, height, true), "png");
    }

    private static BufferedImage noise(int width, int height, boolean withAlpha) {
        BufferedImage image = new BufferedImage(width, height,
                withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        Random random = new Random(width * 31L + height);
        for (int y = 0; y < height; y += BLOCK) {
            for (int x = 0; x < width; x += BLOCK) {
                g.setColor(new Color(random.nextInt(0x1000000)));
                g.fillRect(x, y, BLOCK, BLOCK);
            }
        }
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
