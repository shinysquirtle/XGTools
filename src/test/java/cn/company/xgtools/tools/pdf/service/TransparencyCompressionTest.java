package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.tools.pdf.model.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class TransparencyCompressionTest {
    @TempDir Path directory;

    static Stream<Arguments> modes() {
        return Stream.of(
                Arguments.of(CompressionMode.STANDARD, Quality.SMALL),
                Arguments.of(CompressionMode.STANDARD, Quality.BALANCED),
                Arguments.of(CompressionMode.STANDARD, Quality.CLEAR),
                Arguments.of(CompressionMode.DOWNSAMPLE, Quality.SMALL));
    }

    @ParameterizedTest @MethodSource("modes")
    void transparencyGroupsPreserveTextAndBlendedColors(CompressionMode mode, Quality quality) throws Exception {
        Path input = directory.resolve("transparent.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 200));
            document.addPage(page);
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            group.setItem(COSName.CS, COSName.DEVICERGB);
            group.setBoolean(COSName.I, true);
            page.getCOSObject().setItem(COSName.GROUP, group);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            PDExtendedGraphicsState alpha = new PDExtendedGraphicsState();
            alpha.setNonStrokingAlphaConstant(.5f);
            resources.put(COSName.getPDFName("GS1"), alpha);
            page.setResources(resources);
            String commands = "1 1 1 rg 0 0 300 200 re f\n"
                    + "1 0 0 rg 50 50 100 100 re f\n"
                    + "q /GS1 gs 0 0 1 rg 100 50 100 100 re f Q\n"
                    + "0 0 0 rg BT /F1 16 Tf 20 170 Td (Transparent PDF) Tj ET\n"
                    + ("% " + "padding ".repeat(100) + "\n").repeat(300);
            page.setContents(new PDStream(document, new ByteArrayInputStream(commands.getBytes(StandardCharsets.US_ASCII))));
            document.save(input.toFile());
        }
        byte[] original = Files.readAllBytes(input);
        var options = new PdfOptions(mode, quality, 110, 150, 72, 20);
        var result = new CompressionService().compress(new BundledEngine().executable(), input,
                directory.resolve("out"), options, new AtomicBoolean(), message -> {});
        assertTrue(result.success());
        assertFalse(result.data().originalRetained(), "Must inspect the rewritten PDF, not the source");
        assertArrayEquals(original, Files.readAllBytes(input));
        try (PDDocument before = Loader.loadPDF(input.toFile());
             PDDocument after = Loader.loadPDF(result.data().file().toFile())) {
            assertEquals(1, after.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(after).contains("Transparent PDF"));
            var expected = new PDFRenderer(before).renderImageWithDPI(0, 72);
            var actual = new PDFRenderer(after).renderImageWithDPI(0, 72);
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            for (int x : new int[]{75, 125, 175}) {
                int a = expected.getRGB(x, 100), b = actual.getRGB(x, 100);
                for (int shift : new int[]{0, 8, 16})
                    assertTrue(Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)) <= 12,
                            "Transparency blend color changed at x=" + x);
            }
        }
    }
}

