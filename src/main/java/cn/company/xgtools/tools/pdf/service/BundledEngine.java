package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.MyServiceException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BundledEngine {
    public Path executable() {
        try {
            String application = System.getProperty("jpackage.app-path", "");
            if (!application.isBlank()) return resolve(Path.of(application), null);
            Path location = Path.of(BundledEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path project = location.getParent().getParent();
            if (!Files.isDirectory(project.resolve("vendor/ghostscript")))
                project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            return resolve(null, project);
        } catch (MyServiceException e) { throw e; }
        catch (Exception e) { throw new MyServiceException("无法定位内置压缩引擎，请使用完整的 XGTools 应用目录", e); }
    }

    Path resolve(Path application, Path project) {
        Path root = application != null
                ? application.toAbsolutePath().getParent().resolve("ghostscript")
                : project.toAbsolutePath().resolve("vendor/ghostscript");
        Path executable = root.resolve("bin/gswin64c.exe");
        if (!Files.isRegularFile(executable) || !Files.isRegularFile(root.resolve("bin/gsdll64.dll"))
                || !Files.isDirectory(root.resolve("lib")) || !Files.isDirectory(root.resolve("Resource")))
            throw new MyServiceException("内置压缩引擎缺失或不完整，请使用完整的 XGTools 应用目录");
        return executable;
    }
}
