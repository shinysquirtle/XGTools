package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.MyServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class BundledEngineTest {
    @TempDir Path folder;
    private final BundledEngine engine = new BundledEngine();
    private Path prepare(Path root) throws Exception {
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("Resource"));
        Files.write(root.resolve("bin/gswin64c.exe"), new byte[]{1});
        Files.write(root.resolve("bin/gsdll64.dll"), new byte[]{1});
        return root.resolve("bin/gswin64c.exe");
    }
    @Test void packagedEngineUsesApplicationLocationWithChineseAndSpaces() throws Exception {
        Path app = folder.resolve("中文 目录/XGTools");
        Path expected = prepare(app.resolve("ghostscript"));
        assertEquals(expected, engine.resolve(app.resolve("XGTools.exe"), folder.resolve("irrelevant")));
    }
    @Test void missingPackagedEngineNeverFallsBackToDevelopmentEngine() throws Exception {
        prepare(folder.resolve("vendor/ghostscript"));
        assertThrows(MyServiceException.class, () -> engine.resolve(folder.resolve("release/XGTools.exe"), folder));
    }
    @Test void developmentUsesVendoredEngineAndRequiresDll() throws Exception {
        Path expected = prepare(folder.resolve("vendor/ghostscript"));
        assertEquals(expected, engine.resolve(null, folder));
        Files.delete(expected.resolveSibling("gsdll64.dll"));
        assertThrows(MyServiceException.class, () -> engine.resolve(null, folder));
    }
}
