package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.tools.pdf.model.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AdvancedCompressionTest {
    @TempDir Path directory;
    private final CompressionService service = new CompressionService();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Path engine() {
        Path engine = Path.of(System.getenv().getOrDefault("GHOSTSCRIPT_EXE", "D:/Tools/ghostscript/bin/gswin64c.exe"));
        assumeTrue(Files.isRegularFile(engine)); return engine;
    }
    private PdfOptions options(CompressionMode mode, int target) {
        return new PdfOptions(mode, Quality.BALANCED, 110, 120, 72, target);
    }
    private Path fixture(Path folder, String filename, int pages, boolean padded) throws Exception {
        Files.createDirectories(folder);
        Path file = folder.resolve(filename);
        try (PDDocument document = new PDDocument()) {
            for (int index = 0; index < pages; index++) {
                PDPage page = new PDPage(index == 0 ? new PDRectangle(600, 400)
                        : index == 1 ? new PDRectangle(400, 600) : new PDRectangle(200, 400));
                if (index == 0) page.setCropBox(new PDRectangle(300, 0, 300, 400));
                if (index == 2) page.setRotation(90);
                if (index == 3) page.setUserUnit(2);
                document.addPage(page);
                PDResources resources = new PDResources();
                resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                page.setResources(resources);
                String color = index == 0 ? "1 0 0" : index == 1 ? "0 0 1" : "0 1 0";
                String commands = color + " rg 0 0 600 600 re f\n0 0 0 rg BT /F1 18 Tf 320 100 Td (Hello PDF) Tj ET\n";
                if (padded) commands += ("% " + "padding ".repeat(100) + "\n").repeat(600);
                page.setContents(new PDStream(document, new ByteArrayInputStream(commands.getBytes(StandardCharsets.US_ASCII))));
            }
            document.save(file.toFile());
        }
        return file;
    }

    @Test void diagnosticFindsContentCropAndPhysicalDimensions() throws Exception {
        Path input = fixture(directory, "诊断.pdf", 4, true);
        var result = new PdfInspector().analyze(input, cancelled);
        assertEquals(4, result.pages().size()); assertEquals(1, result.croppedPages());
        assertTrue(result.contentBytes() > 1_000_000);
        assertEquals(300, result.pages().get(0).width(), .01);
        assertEquals(400, result.pages().get(2).width(), .01);
        assertEquals(200, result.pages().get(2).height(), .01);
        assertEquals(400, result.pages().get(3).width(), .01);
        assertEquals(800, result.pages().get(3).height(), .01);
    }
    @Test void rasterPreservesCropRotationUserUnitAndColors() throws Exception {
        Path input = fixture(directory, "裁切 横竖混合.pdf", 4, true);
        byte[] original = Files.readAllBytes(input);
        var result = service.compress(engine(), input, directory.resolve("output"), options(CompressionMode.RASTER,20), cancelled, m -> {});
        assertTrue(result.success()); assertFalse(result.data().originalRetained());
        assertEquals(120, result.data().dpi());
        assertArrayEquals(original, Files.readAllBytes(input));
        var before = new PdfInspector().analyze(input, cancelled);
        var after = new PdfInspector().analyze(result.data().file(), cancelled);
        assertEquals(before.pages(), after.pages());
        try (PDDocument document = Loader.loadPDF(result.data().file().toFile())) {
            assertEquals(4, document.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(document).isBlank());
            PDFRenderer renderer = new PDFRenderer(document);
            int[] expectedChannel = {16, 0, 8, 8};
            for (int i = 0; i < 4; i++) {
                var image = renderer.renderImageWithDPI(i, 36);
                int pixel = image.getRGB(image.getWidth()/2, image.getHeight()/2);
                assertTrue(((pixel >> expectedChannel[i]) & 255) > 220);
                int corner = image.getRGB(3, 3);
                assertTrue(((corner >> expectedChannel[i]) & 255) > 220, "不得出现裁切外的空白边");
            }
        }
        try (var paths = Files.list(directory.resolve("output"))) { assertEquals(1, paths.count()); }
    }
    @Test void downsamplingPreservesText() throws Exception {
        Path input = fixture(directory, "文字.pdf", 1, true);
        var result = service.compress(engine(), input, directory.resolve("out"), options(CompressionMode.DOWNSAMPLE,20), cancelled, m -> {});
        assertTrue(result.success()); assertFalse(result.data().originalRetained());
        try (PDDocument document = Loader.loadPDF(result.data().file().toFile())) {
            assertTrue(new PDFTextStripper().getText(document).contains("Hello PDF"));
        }
    }
    @Test void automaticModeSkipsAlreadyQualifiedAndRasterizesLargeContent() throws Exception {
        Path tiny = fixture(directory, "tiny.pdf", 1, false);
        var skipped = service.compress(engine(), tiny, directory.resolve("skip"), options(CompressionMode.TARGET,20), cancelled, m -> {});
        assertTrue(skipped.data().originalRetained()); assertTrue(skipped.data().targetMet());
        assertFalse(Files.exists(directory.resolve("skip")));
        Path large = fixture(directory, "large.pdf", 4, true);
        var result = service.compress(engine(), large, directory.resolve("target"), options(CompressionMode.TARGET,1), cancelled, m -> {});
        assertTrue(result.data().targetMet()); assertFalse(result.data().originalRetained());
        assertEquals("整页栅格化", result.data().method()); assertEquals(150, result.data().dpi());
        assertTrue(result.data().outputBytes() <= 1048576);
    }
    @Test void sameBasenameDifferentFoldersCannotMixPages() throws Exception {
        Path a = fixture(directory.resolve("甲"), "same.pdf", 4, true);
        Path b = fixture(directory.resolve("乙"), "same.pdf", 1, true);
        Path out = directory.resolve("out");
        var first = service.compress(engine(), a, out, options(CompressionMode.RASTER,20), cancelled, m -> {});
        var second = service.compress(engine(), b, out, options(CompressionMode.RASTER,20), cancelled, m -> {});
        assertNotEquals(first.data().file(), second.data().file());
        try (PDDocument document = Loader.loadPDF(second.data().file().toFile())) { assertEquals(1, document.getNumberOfPages()); }
        try (var paths = Files.list(out)) { assertEquals(2, paths.count()); }
    }
    @Test void truncatedFileIsRejectedEvenWhenParserCouldRepairIt() throws Exception {
        Path input = fixture(directory, "truncated.pdf", 1, false);
        String bytes = new String(Files.readAllBytes(input), StandardCharsets.ISO_8859_1);
        Files.write(input, bytes.substring(0, bytes.lastIndexOf("%%EOF")).getBytes(StandardCharsets.ISO_8859_1));
        assertThrows(MyServiceException.class, () -> new PdfInspector().analyze(input, cancelled));
    }
    @Test void cancellationDuringRasterLeavesNoArtifacts() throws Exception {
        Path input = fixture(directory, "cancel.pdf", 2, true);
        Path output = directory.resolve("cancel-out");
        var result = service.compress(engine(), input, output, options(CompressionMode.RASTER,20), cancelled,
                message -> { if (message.startsWith("整页渲染")) cancelled.set(true); });
        assertFalse(result.success());
        try (var paths = Files.list(output)) { assertEquals(0, paths.count()); }
    }
    @Test void automaticModeReportsUnmetTargetAfterTryingEveryDpi() throws Exception {
        Path input = directory.resolve("noisy.pdf");
        try (PDDocument document = new PDDocument()) {
            Random random = new Random(135);
            for (int pageIndex = 0; pageIndex < 12; pageIndex++) {
                var pixels = new java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 800; y++) for (int x = 0; x < 600; x++) pixels.setRGB(x, y, random.nextInt(0x1000000));
                PDPage page = new PDPage(new PDRectangle(432, 576)); document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(document, pixels, 1f), 0, 0, 432, 576);
                }
            }
            document.save(input.toFile());
        }
        List<String> messages = new ArrayList<>();
        var result = service.compress(engine(), input, directory.resolve("unmet"),
                new PdfOptions(CompressionMode.TARGET, Quality.BALANCED, 110, 150, 95, 1), cancelled, messages::add);
        assertTrue(result.success()); assertFalse(result.data().targetMet());
        assertTrue(Files.isRegularFile(result.data().file()));
        assertTrue(result.data().outputBytes() > 1048576);
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("80 DPI 成品")));
        assertTrue(result.message().contains("未达到目标"));
    }
    @Test void timeoutAndInvalidOptionsUseUnifiedException() throws Exception {
        Path input = fixture(directory, "timeout.pdf", 1, false);
        assertThrows(MyServiceException.class, () -> new CompressionService(Duration.ZERO).compress(engine(), input,
                directory.resolve("out"), options(CompressionMode.RASTER,20), cancelled, m -> {}));
        assertThrows(MyServiceException.class, () -> new PdfOptions(CompressionMode.RASTER, Quality.BALANCED,110,30,72,20));
    }
}

