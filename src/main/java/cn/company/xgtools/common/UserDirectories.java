package cn.company.xgtools.common;

import javax.swing.filechooser.FileSystemView;
import java.nio.file.Path;

public final class UserDirectories {
    private UserDirectories() {}

    /** 使用 Windows Shell 的桌面路径，支持桌面重定向和 OneDrive。 */
    public static Path desktop() {
        var desktop = FileSystemView.getFileSystemView().getHomeDirectory();
        if (desktop == null) throw new MyServiceException("无法获取当前用户桌面目录");
        return desktop.toPath().toAbsolutePath();
    }
}