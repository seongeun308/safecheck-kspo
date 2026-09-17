package io.github.seongeun308.safecheck.support;

import io.github.seongeun308.safecheck.config.SafecheckProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImagePreprocessorTest {

    private static final int MAX_DIMENSION = 1024;

    private final ImagePreprocessor preprocessor = preprocessorWith(MAX_DIMENSION, 10);

    // ------------------------------------------------------------------
    // 리사이즈
    // ------------------------------------------------------------------

    @Test
    @DisplayName("장변이 기준 크기로 축소된다")
    void resizesLongSideToMaxDimension() {
        byte[] original = jpeg(4032, 3024);

        var prepared = preprocessor.prepare(original);

        assertThat(Math.max(prepared.width(), prepared.height())).isEqualTo(MAX_DIMENSION);
    }

    @Test
    @DisplayName("세로 사진도 장변 기준으로 축소된다")
    void resizesPortraitByLongSide() {
        byte[] original = jpeg(3024, 4032);

        var prepared = preprocessor.prepare(original);

        assertThat(prepared.height()).isEqualTo(MAX_DIMENSION);
        assertThat(prepared.width()).isLessThan(prepared.height());
    }

    @Test
    @DisplayName("가로세로 비율이 유지된다")
    void preservesAspectRatio() {
        byte[] original = jpeg(4000, 2000);

        var prepared = preprocessor.prepare(original);

        double ratio = (double) prepared.width() / prepared.height();
        assertThat(ratio).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("기준 크기보다 작은 이미지는 확대하지 않는다")
    void doesNotUpscaleSmallImage() {
        // 공단 개방 이미지의 중앙값 해상도
        byte[] original = jpeg(330, 248);

        var prepared = preprocessor.prepare(original);

        assertThat(prepared.width()).isEqualTo(330);
        assertThat(prepared.height()).isEqualTo(248);
    }

    @Test
    @DisplayName("전처리 후 해상도가 축소된다")
    void reducesDimension() {
        byte[] original = jpeg(4032, 3024);

        var prepared = preprocessor.prepare(original);

        assertThat(prepared.width()).isLessThan(4032);
        assertThat(prepared.height()).isLessThan(3024);
    }

    // ------------------------------------------------------------------
    // 출력 형식
    // ------------------------------------------------------------------

    @Test
    @DisplayName("출력은 항상 JPEG이다")
    void alwaysOutputsJpeg() {
        var fromJpeg = preprocessor.prepare(jpeg(800, 600));
        var fromPng = preprocessor.prepare(png(800, 600, false));

        assertThat(fromJpeg.mediaType()).isEqualTo(ImagePreprocessor.JPEG);
        assertThat(fromPng.mediaType()).isEqualTo(ImagePreprocessor.JPEG);
    }

    @Test
    @DisplayName("투명도가 있는 PNG도 변환에 실패하지 않는다")
    void handlesTransparentPng() {
        byte[] original = png(800, 600, true);

        var prepared = preprocessor.prepare(original);

        assertThat(prepared.bytes()).isNotEmpty();
        assertThat(prepared.mediaType()).isEqualTo(ImagePreprocessor.JPEG);
    }

    // ------------------------------------------------------------------
    // 해시
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 원본은 같은 해시를 낸다")
    void sameImageProducesSameHash() {
        byte[] original = jpeg(800, 600);

        String first = preprocessor.prepare(original).hash();
        String second = preprocessor.prepare(original).hash();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("다른 원본은 다른 해시를 낸다")
    void differentImagesProduceDifferentHash() {
        String first = preprocessor.prepare(jpeg(800, 600)).hash();
        String second = preprocessor.prepare(jpeg(640, 480)).hash();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("해시는 64자리 16진수다")
    void hashIsSha256Hex() {
        String hash = preprocessor.prepare(jpeg(800, 600)).hash();

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    // ------------------------------------------------------------------
    // 입력 검증
    // ------------------------------------------------------------------

    @Test
    @DisplayName("빈 입력을 거부한다")
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> preprocessor.prepare(new byte[0]))
                .isInstanceOf(ImagePreprocessor.InvalidImageException.class);

        assertThatThrownBy(() -> preprocessor.prepare(null))
                .isInstanceOf(ImagePreprocessor.InvalidImageException.class);
    }

    @Test
    @DisplayName("이미지가 아닌 파일을 거부한다")
    void rejectsNonImage() {
        byte[] text = "이것은 이미지가 아닙니다".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> preprocessor.prepare(text))
                .isInstanceOf(ImagePreprocessor.InvalidImageException.class)
                .hasMessageContaining("JPG 또는 PNG");
    }

    @Test
    @DisplayName("확장자만 이미지인 파일을 거부한다")
    void rejectsDisguisedFile() {
        // 실제 내용은 PDF. 확장자나 Content-Type이 아니라 선두 바이트로 판별한다.
        byte[] fakePdf = "%PDF-1.4\n%fake content".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> preprocessor.prepare(fakePdf))
                .isInstanceOf(ImagePreprocessor.InvalidImageException.class);
    }

    @Test
    @DisplayName("허용 용량을 초과하면 거부한다")
    void rejectsOversizedUpload() {
        ImagePreprocessor limited = preprocessorWith(MAX_DIMENSION, 1);
        byte[] oversized = new byte[2 * 1024 * 1024];

        assertThatThrownBy(() -> limited.prepare(oversized))
                .isInstanceOf(ImagePreprocessor.InvalidImageException.class)
                .hasMessageContaining("1MB");
    }

    // ------------------------------------------------------------------
    // 실제 촬영본 (선택)
    // ------------------------------------------------------------------

    /**
     * 휴대폰으로 세로 촬영한 사진을 {@code src/test/resources/samples/portrait.jpg}에
     * 두면 EXIF 방향 보정을 확인한다. 파일이 없으면 건너뛴다.
     *
     * <p>회전이 제대로 되었는지는 결과 파일을 직접 열어 확인해야 한다.
     * 자동 검증으로는 세로가 세로로 나오는지까지만 본다.
     */
    @Test
    @DisplayName("세로로 촬영한 사진이 세로로 처리된다")
    void appliesExifOrientation() throws IOException {
        Path sample = Path.of("src/test/resources/samples/portrait.jpeg");
        assumeTrue(Files.exists(sample), "샘플 사진이 없어 건너뜁니다: " + sample);

        var prepared = preprocessor.prepare(Files.readAllBytes(sample));

        assertThat(prepared.height())
                .as("세로 사진이므로 높이가 너비보다 커야 한다")
                .isGreaterThan(prepared.width());

        // 눈으로 확인할 수 있도록 결과를 남긴다
        Path out = Path.of("build/tmp/prepared-portrait.jpg");
        Files.createDirectories(out.getParent());
        Files.write(out, prepared.bytes());
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private static ImagePreprocessor preprocessorWith(int maxDimension, int maxUploadMb) {
        var properties = new SafecheckProperties(
                new SafecheckProperties.Llm(
                        "test-key", "http://localhost", "test-model",
                        1000, 0.0, java.time.Duration.ofSeconds(60), 1),
                new SafecheckProperties.Image(maxDimension, maxUploadMb),
                new SafecheckProperties.Judgement(0.5, 3, 3));
        return new ImagePreprocessor(properties);
    }

    /** 단색이면 JPEG가 지나치게 압축되므로 노이즈를 섞어 실제 사진에 가깝게 만든다. */
    private static byte[] jpeg(int width, int height) {
        return encode(noiseImage(width, height, false), "jpeg");
    }

    private static byte[] png(int width, int height, boolean withAlpha) {
        return encode(noiseImage(width, height, withAlpha), "png");
    }

    private static BufferedImage noiseImage(int width, int height, boolean withAlpha) {
        BufferedImage image = new BufferedImage(width, height,
                withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        Random random = new Random(width * 31L + height);
        for (int y = 0; y < height; y += 8) {
            for (int x = 0; x < width; x += 8) {
                g.setColor(new Color(random.nextInt(0x1000000)));
                g.fillRect(x, y, 8, 8);
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