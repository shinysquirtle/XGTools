package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.MyServiceException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class GhostscriptRunner {
    static void run(List<String> command, Path log, long deadline, AtomicBoolean cancelled, Consumer<String> progress) {
        Process process = null;
        try {
            check(deadline, cancelled);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            Path engineRoot = Path.of(command.getFirst()).toAbsolutePath().getParent().getParent();
            // 覆盖 Windows 注册表或外部环境配置，所有引擎资源来自本应用。
            builder.environment().put("GS_LIB", String.join(java.io.File.pathSeparator,
                    engineRoot.resolve("lib").toString(), engineRoot.resolve("Resource/Init").toString(),
                    engineRoot.resolve("Resource/Font").toString(), "%rom%Resource/Init/", "%rom%lib/"));
            builder.environment().remove("GS_OPTIONS");
            builder.environment().remove("GS_FONTPATH");
            builder.environment().remove("GS_DLL");
            process = builder.start();
            long nextNotice = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!process.waitFor(150, TimeUnit.MILLISECONDS)) {
                check(deadline, cancelled);
                if (System.nanoTime() >= nextNotice) {
                    progress.accept("Ghostscript 正在处理，请稍候…");
                    nextNotice = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                }
            }
            check(deadline, cancelled);
            String tail;
            try (var channel = Files.newByteChannel(log)) {
                channel.position(Math.max(0, channel.size() - 4096));
                ByteBuffer buffer = ByteBuffer.allocate(4096); channel.read(buffer); buffer.flip();
                tail = StandardCharsets.UTF_8.decode(buffer).toString().trim();
            }
            if (process.exitValue() != 0) throw new MyServiceException("Ghostscript 处理失败：" + tail);
            if (tail.contains("downsampling aborted")) progress.accept("警告：Ghostscript 部分图像降采样未生效");
        } catch (MyServiceException | CancellationException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch (Exception e) { throw new MyServiceException("Ghostscript 调用失败：" + e.getMessage(), e); }
        finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
    static void check(long deadline, AtomicBoolean cancelled) {
        PdfInspector.check(cancelled);
        if (System.nanoTime() >= deadline) throw new MyServiceException("PDF 处理超时，请降低参数或拆分文件");
    }
}
