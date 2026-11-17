package cn.company.xgtools.tools.image.service;

import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.tools.image.model.ImageOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ImageProcessingServiceTest {
    @TempDir Path directory;
    private final ImageProcessingService service = new ImageProcessingService();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private ImageOptions options(boolean compress, boolean watermark, float quality) {
        return new ImageOptions(compress, watermark, quality, null);
    }
    private Path picture(String name, boolean transparent) throws Exception {
        BufferedImage image = new BufferedImage(900, 700, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (!transparent) {
            Random random = new Random(17);
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt(0x1000000));
        }
        Path file = directory.resolve(name); ImageIO.write(image, "png", file.toFile()); return file;
    }
    @Test void compressionUsesQualityAndKeepsDimensionsAndOriginal() throws Exception {
        Path input = picture("中文 空格.png", false);
        byte[] original = Files.readAllBytes(input);
        var low = service.process(input, directory.resolve("out"), options(true, false, .2f), cancelled);
        var high = service.process(input, directory.resolve("out"), options(true, false, .9f), cancelled);
        assertTrue(low.success()); assertTrue(high.success());
        assertTrue(low.data().outputBytes() < high.data().outputBytes());
        assertTrue(low.data().file().toString().endsWith(".jpg"));
        var output = ImageIO.read(low.data().file().toFile());
        assertEquals(900, output.getWidth()); assertEquals(700, output.getHeight());
        assertArrayEquals(original, Files.readAllBytes(input));
        assertNotEquals(low.data().file(), high.data().file());
    }
    @Test void transparentPixelsBecomeWhiteInJpeg() throws Exception {
        Path input = picture("透明.png", true);
        var result = service.process(input, directory.resolve("out"), options(true, false, .2f), cancelled);
        BufferedImage output = ImageIO.read(result.data().file().toFile());
        Color corner = new Color(output.getRGB(0, 0));
        assertTrue(corner.getRed() > 245 && corner.getGreen() > 245 && corner.getBlue() > 245);
        assertFalse(output.getColorModel().hasAlpha());
    }
    @Test void defaultWatermarkIsBundledAndPngRetainsTransparency() throws Exception {
        try (var stream = getClass().getResourceAsStream(ImageProcessingService.DEFAULT_WATERMARK)) {
            assertNotNull(stream); assertNotNull(ImageIO.read(stream));
        }
        Path input = picture("透明水印.png", true);
        var result = service.process(input, directory.resolve("out"), options(false, true, .2f), cancelled);
        assertTrue(result.success()); assertTrue(result.data().file().toString().endsWith(".png"));
        BufferedImage output = ImageIO.read(result.data().file().toFile());
        assertTrue(output.getColorModel().hasAlpha());
        int transparent = 0, marked = 0;
        for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++) {
            if ((output.getRGB(x, y) >>> 24) == 0) transparent++; else marked++;
        }
        assertTrue(transparent > 0); assertTrue(marked > 0, "默认水印必须实际绘制");
    }
    @Test void combinedOperationEqualsWatermarkThenCompression() throws Exception {
        Path input = picture("组合.png", false);
        Path marked = service.process(input, directory.resolve("marked"), options(false, true, .2f), cancelled).data().file();
        Path sequential = service.process(marked, directory.resolve("sequential"), options(true, false, .2f), cancelled).data().file();
        Path combined = service.process(input, directory.resolve("combined"), options(true, true, .2f), cancelled).data().file();
        assertArrayEquals(Files.readAllBytes(sequential), Files.readAllBytes(combined));
    }
    @Test void customWatermarkIsUsed() throws Exception {
        Path input = picture("自定义.png", true);
        BufferedImage logo = new BufferedImage(100, 25, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = logo.createGraphics();
        graphics.setColor(Color.RED); graphics.fillRect(0, 0, 100, 25); graphics.dispose();
        Path watermark = directory.resolve("red.png"); ImageIO.write(logo, "png", watermark.toFile());
        var result = service.process(input, directory.resolve("out"), new ImageOptions(false, true, .2f, watermark), cancelled);
        BufferedImage output = ImageIO.read(result.data().file().toFile());
        boolean foundRed = false;
        for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++)
            if (output.getRGB(x, y) == Color.RED.getRGB()) foundRed = true;
        assertTrue(foundRed);
    }
    @Test void sameNameNeverOverwritesAndTemporaryFilesAreCleaned() throws Exception {
        Path input = picture("same.png", false);
        Path out = directory.resolve("out");
        Path first = service.process(input, out, options(true, true, .2f), cancelled).data().file();
        byte[] originalOutput = Files.readAllBytes(first);
        Path second = service.process(input, out, options(true, true, .2f), cancelled).data().file();
        assertNotEquals(first, second); assertArrayEquals(originalOutput, Files.readAllBytes(first));
        try (var paths = Files.list(out)) { assertEquals(2, paths.count()); }
    }
    @Test void invalidInputAndSettingsFailWithUnifiedException() throws Exception {
        Path corrupt = directory.resolve("broken.png"); Files.writeString(corrupt, "broken");
        assertThrows(MyServiceException.class, () -> service.process(corrupt, directory.resolve("out"), options(true, false, .2f), cancelled));
        assertThrows(MyServiceException.class, () -> options(false, false, .2f));
        assertThrows(MyServiceException.class, () -> options(true, false, Float.NaN));
        assertThrows(MyServiceException.class, () -> options(true, false, 0));
        Path input = picture("input.png", false);
        assertThrows(MyServiceException.class, () -> service.process(input, directory.resolve("out"),
                new ImageOptions(false, true, .2f, directory.resolve("missing.png")), cancelled));
        assertFalse(Files.exists(directory.resolve("out")));
    }
    @Test void cancellationDoesNotCreateOutput() {
        var result = service.process(directory.resolve("missing.png"), directory.resolve("out"),
                options(true, true, .2f), new AtomicBoolean(true));
        assertFalse(result.success()); assertFalse(Files.exists(directory.resolve("out")));
    }
}
