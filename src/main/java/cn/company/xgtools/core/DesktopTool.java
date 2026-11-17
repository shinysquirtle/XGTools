package cn.company.xgtools.core;

import javafx.scene.Node;
import javafx.stage.Stage;

/** 桌面工具扩展点；工具负责自己的界面、后台任务及关闭清理。 */
public interface DesktopTool extends AutoCloseable {
    String id();
    String name();
    String description();
    Node createView(Stage owner);
    default boolean isBusy() { return false; }
    @Override default void close() {}
}
