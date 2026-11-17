package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.tools.pdf.model.Quality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompressionServiceTest {
    @TempDir Path directory;
    private final CompressionService service = new CompressionService();
    private Path engine() {
        Path path = Path.of(System.getenv().getOrDefault("GHOSTSCRIPT_EXE", "D:/Tools/ghostscript/bin/gswin64c.exe"));
        assumeTrue(Files.isRegularFile(path), "未安装 Ghostscript，跳过集成测试");
        return path;
    }
    @Test void cancellationDoesNotCreateFiles() throws Exception {
        var result = service.compress(Path.of("missing.exe"), Path.of("missing.pdf"), directory.resolve("output"),
                Quality.BALANCED, new AtomicBoolean(true));
        assertFalse(result.success());
        assertFalse(Files.exists(directory.resolve("output")));
    }
    @Test void invalidInputUsesUnifiedException() throws Exception {
        Path input = directory.resolve("bad.pdf");
        Files.writeString(input, "not a pdf");
        assertThrows(MyServiceException.class, () -> service.compress(engine(), input, directory,
                Quality.BALANCED, new AtomicBoolean()));
    }
    @Test void compressesChinesePathsAndNeverOverwrites() throws Exception {
        Path input = directory.resolve("中文 空格.pdf");
        createPdf(input, true);
        byte[] original = Files.readAllBytes(input);
        Path destination = directory.resolve("输出 目录");
        var first = service.compress(engine(), input, destination, Quality.BALANCED, new AtomicBoolean());
        assertTrue(first.success());
        assertTrue(first.data().outputBytes() < first.data().originalBytes());
        byte[] firstBytes = Files.readAllBytes(first.data().file());
        var second = service.compress(engine(), input, destination, Quality.BALANCED, new AtomicBoolean());
        assertTrue(second.success());
        assertNotEquals(first.data().file(), second.data().file());
        assertArrayEquals(firstBytes, Files.readAllBytes(first.data().file()));
        assertArrayEquals(original, Files.readAllBytes(input));
        try (var children = Files.list(destination)) {
            assertEquals(2, children.count(), "不得残留临时文件或日志");
        }
    }
    @Test void retainsOriginalWhenOutputWouldBeLarger() throws Exception {
        Path input = directory.resolve("tiny.pdf");
        createPdf(input, false);
        var result = service.compress(engine(), input, directory.resolve("output"), Quality.CLEAR, new AtomicBoolean());
        assertTrue(result.success());
        assertEquals(input, result.data().file());
        assertEquals(result.data().originalBytes(), result.data().outputBytes());
        try (var children = Files.list(directory.resolve("output"))) { assertEquals(0, children.count()); }
    }
    @Test void damagedPdfReportsErrorAndCleansTemporaryFiles() throws Exception {
        Path input = directory.resolve("damaged.pdf");
        Files.writeString(input, "%PDF-1.4\ninvalid objects");
        Path destination = directory.resolve("output");
        assertThrows(MyServiceException.class, () -> service.compress(engine(), input, destination,
                Quality.BALANCED, new AtomicBoolean()));
        if (Files.exists(destination)) {
            try (var children = Files.list(destination)) { assertEquals(0, children.count()); }
        }
    }
    private static void createPdf(Path path, boolean padded) throws Exception {
        String content = (padded ? ("% " + "padding ".repeat(100) + "\n").repeat(300) : "")
                + "BT /F1 24 Tf 50 700 Td (Compression test) Tj ET\n";
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + content + "endstream"
        };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(bytes.size());
            bytes.write(((i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        int xref = bytes.size();
        bytes.write("xref\n0 6\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) bytes.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        bytes.write(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        Files.write(path, bytes.toByteArray());
    }
}
