package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.tools.pdf.model.PdfOptions;
import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.nio.file.Path;

public final class SettingsService {
    private final Path directory = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")), "XGTools");
    public Path directory() { return directory; }
    public JSONObject load() {
        Path file = directory.resolve("settings.json");
        if (!FileUtil.exist(file.toFile())) return new JSONObject();
        try { return JSONUtil.parseObj(FileUtil.readUtf8String(file.toFile())); }
        catch (Exception e) { throw new MyServiceException("配置文件读取失败：" + file, e); }
    }
    public void save(String output, PdfOptions options) {
        try {
            JSONObject json = new JSONObject().set("output", output).set("quality", options.quality().name())
                    .set("mode", options.mode().name()).set("imageDpi", options.imageDpi()).set("rasterDpi", options.rasterDpi())
                    .set("jpegQuality", options.jpegQuality()).set("targetMb", options.targetMb());
            FileUtil.writeUtf8String(JSONUtil.toJsonPrettyStr(json), directory.resolve("settings.json").toFile());
        } catch (Exception e) { throw new MyServiceException("保存配置失败", e); }
    }
}
