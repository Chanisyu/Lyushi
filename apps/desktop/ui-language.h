#pragma once
#include <QHash>
#include <QSet>
#include <QTranslator>

/**
 * 当前只提供简体中文。补齐 Qt 通用按钮和文件表单用语，不依赖额外语言包。
 * 只翻译 Qt 自己的界面上下文，用户事项、文件名和数据库内容不经过此映射。
 */
class SimplifiedChineseTranslator : public QTranslator {
public:
    bool isEmpty() const override { return false; }
    QString translate(const char *context, const char *source, const char * = nullptr, int = -1) const override
    {
        static const QSet<QString> contexts{"QPlatformTheme", "QDialogButtonBox", "QMessageBox", "QFileDialog", "QInputDialog"};
        if (!contexts.contains(QString::fromUtf8(context))) return {};
        QString key = QString::fromUtf8(source); key.remove('&');
        static const QHash<QString,QString> phrases{
            {"OK", "确定"}, {"Save", "保存"}, {"Cancel", "取消"}, {"Close", "关闭"},
            {"Yes", "是"}, {"No", "否"}, {"Open", "打开"}, {"Apply", "应用"},
            {"Discard", "放弃"}, {"Reset", "重置"}, {"Retry", "重试"}, {"Ignore", "忽略"},
            {"Save All", "全部保存"}, {"Yes to All", "全部是"}, {"No to All", "全部否"},
            {"Restore Defaults", "恢复默认"}, {"File name:", "文件名："}, {"Files of type:", "文件类型："},
            {"Look in:", "查找位置："}, {"Save in:", "保存位置："}, {"File", "文件"}, {"Folder", "文件夹"},
            {"%1 already exists.\nDo you want to replace it?", "%1 已存在。\n要替换它吗？"}
        };
        return phrases.value(key);
    }
};
