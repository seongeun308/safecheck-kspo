package io.github.seongeun308.safecheck.support;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import io.github.seongeun308.safecheck.config.ImageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * 업로드된 사진을 판정 모델에 보낼 수 있는 형태로 가공한다.
 *
 * <p>수행하는 일은 네 가지다.
 * <ul>
 *   <li>매직 넘버로 실제 이미지 형식을 판별한다. 클라이언트가 보낸
 *       Content-Type이나 확장자는 신뢰하지 않는다.
 *   <li>EXIF 방향 태그를 반영해 실제로 회전시킨다. 휴대폰으로 세로로 찍은
 *       사진은 가로로 저장되고 태그로만 방향을 표시하는데, ImageIO는 이를
 *       무시하므로 처리하지 않으면 모델이 돌아간 사진을 보게 된다.
 *   <li>장변을 기준 크기로 축소한다. 원본이 4000px급이면 토큰이 수 배로
 *       늘어난다.
 *   <li>캐시 키로 쓸 원본 해시를 계산한다.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImagePreprocessor {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";

    /** 리사이즈 결과는 항상 JPEG로 낸다. 점검 사진에 투명도가 필요 없다. */
    private static final String OUTPUT_FORMAT = "jpeg";
    private static final float JPEG_QUALITY = 0.85f;

    private final ImageProperties properties;

    /**
     * @param original 업로드된 원본 바이트
     * @throws InvalidImageException 지원하지 않는 형식이거나 손상된 경우
     */
    public PreparedImage prepare(byte[] original) {
        if (original == null || original.length == 0) {
            throw new InvalidImageException("이미지가 비어 있습니다.");
        }

        long maxBytes = (long) properties.maxUploadMb() * 1024 * 1024;
        if (original.length > maxBytes) {
            throw new InvalidImageException(
                    "이미지가 너무 큽니다. 최대 %dMB까지 올릴 수 있습니다."
                            .formatted(properties.maxUploadMb()));
        }

        String sourceType = detectMediaType(original);
        String hash = sha256(original);

        BufferedImage image = read(original);
        int originalWidth = image.getWidth();
        int originalHeight = image.getHeight();

        image = applyExifOrientation(image, original);
        image = resize(image, properties.maxDimension());

        byte[] encoded = encodeJpeg(image);

        log.debug("이미지 전처리: {} {}x{} {}B → JPEG {}x{} {}B",
                sourceType, originalWidth, originalHeight, original.length,
                image.getWidth(), image.getHeight(), encoded.length);

        return new PreparedImage(encoded, JPEG, hash, image.getWidth(), image.getHeight());
    }

    // ------------------------------------------------------------------
    // 형식 판별
    // ------------------------------------------------------------------

    /** 확장자가 아니라 선두 바이트로 판별한다. */
    private static String detectMediaType(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return PNG;
        }
        throw new InvalidImageException("JPG 또는 PNG 형식만 올릴 수 있습니다.");
    }

    private static BufferedImage read(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new InvalidImageException("이미지를 읽을 수 없습니다. 파일이 손상되었을 수 있습니다.");
            }
            return image;
        } catch (IOException e) {
            throw new InvalidImageException("이미지를 읽을 수 없습니다.", e);
        }
    }

    // ------------------------------------------------------------------
    // EXIF 방향 보정
    // ------------------------------------------------------------------

    private static BufferedImage applyExifOrientation(BufferedImage image, byte[] original) {
        int orientation = readOrientation(original);
        if (orientation <= 1) {
            return image;
        }

        int w = image.getWidth();
        int h = image.getHeight();
        AffineTransform tx = new AffineTransform();
        boolean swapAxis = false;

        switch (orientation) {
            case 2 -> {                                   // 좌우 반전
                tx.scale(-1, 1);
                tx.translate(-w, 0);
            }
            case 3 -> {                                   // 180도
                tx.translate(w, h);
                tx.rotate(Math.PI);
            }
            case 4 -> {                                   // 상하 반전
                tx.scale(1, -1);
                tx.translate(0, -h);
            }
            case 5 -> {                                   // 전치
                tx.rotate(-Math.PI / 2);
                tx.scale(-1, 1);
                swapAxis = true;
            }
            case 6 -> {                                   // 시계 90도
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2);
                swapAxis = true;
            }
            case 7 -> {                                   // 역전치
                tx.scale(-1, 1);
                tx.translate(-h, 0);
                tx.translate(0, w);
                tx.rotate(3 * Math.PI / 2);
                swapAxis = true;
            }
            case 8 -> {                                   // 반시계 90도
                tx.translate(0, w);
                tx.rotate(3 * Math.PI / 2);
                swapAxis = true;
            }
            default -> {
                return image;
            }
        }

        BufferedImage rotated = new BufferedImage(
                swapAxis ? h : w, swapAxis ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, tx, null);
        g.dispose();

        log.debug("EXIF 방향 {} 적용", orientation);
        return rotated;
    }

    /** 태그가 없거나 읽을 수 없으면 1(정방향)로 본다. */
    private static int readOrientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (ImageProcessingException | IOException | com.drew.metadata.MetadataException e) {
            log.debug("EXIF 방향을 읽지 못했습니다. 정방향으로 처리합니다.", e);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 리사이즈 / 인코딩
    // ------------------------------------------------------------------

    /** 장변이 기준보다 작으면 그대로 둔다. 확대하지 않는다. */
    private static BufferedImage resize(BufferedImage image, int maxDimension) {
        int w = image.getWidth();
        int h = image.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= maxDimension) {
            return toRgb(image);
        }

        double ratio = (double) maxDimension / longSide;
        int newWidth = Math.max(1, (int) Math.round(w * ratio));
        int newHeight = Math.max(1, (int) Math.round(h * ratio));

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return resized;
    }

    /** PNG의 알파 채널을 JPEG로 내보내면 색이 깨지므로 RGB로 바꾼다. */
    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        return rgb;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(OUTPUT_FORMAT);
        if (!writers.hasNext()) {
            throw new IllegalStateException("JPEG 인코더를 찾을 수 없습니다.");
        }
        ImageWriter writer = writers.next();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new InvalidImageException("이미지를 변환하지 못했습니다.", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // 해시
    // ------------------------------------------------------------------

    /**
     * 캐시 키. 리사이즈 전 원본으로 계산한다.
     * 리사이즈 결과는 실행 환경에 따라 미세하게 달라질 수 있다.
     */
    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    // ------------------------------------------------------------------

    /**
     * @param bytes     판정 모델에 보낼 JPEG 바이트
     * @param mediaType 항상 image/jpeg
     * @param hash      원본 SHA-256. 캐시 키로 쓴다.
     */
    public record PreparedImage(
            byte[] bytes,
            String mediaType,
            String hash,
            int width,
            int height
    ) {}

    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }

        public InvalidImageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
