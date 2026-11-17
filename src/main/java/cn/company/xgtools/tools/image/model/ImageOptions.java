package cn.company.xgtools.tools.image.model;

import cn.company.xgtools.common.MyServiceException;
import java.nio.file.Path;

public record ImageOptions(boolean compress, boolean watermark, float quality, Path customWatermark) {
    public ImageOptions {
        if (!compress && !watermark) throw new MyServiceException("请至少选择压缩或添加水印");
        if (!Float.isFinite(quality) || quality <= 0 || quality > 1)
            throw new MyServiceException("压缩质量必须在 1%～100% 之间");
    }
}
