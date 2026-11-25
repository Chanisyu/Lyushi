#include "store.h"
#include <QDir>
#include <QJsonDocument>

/** 使用独立数据库验证完整任务生命周期，所有时间取自同一次开始记录，避免依赖等待。 */
void focusSelfTest(const QString &directory)
{
    const auto profile = QDir(directory).filePath("focus-check");
    QString id, book; qint64 start = 0;
    auto rejects = [](const std::function<void()> &action) { bool failed = false; try { action(); } catch (...) { failed = true; } ensure(failed, "invalid focus transition accepted"); };
    {
        Store store(profile); book = store.addLedger(QStringLiteral("分心验收")); store.addTag(QStringLiteral("学习")); store.addTag(QStringLiteral("生活"));
        store.start(book, QStringLiteral("背单词"), QStringLiteral("学习")); auto current = store.active(); id = current["id"].toString(); start = current["started"].toInteger();
        // 未确认的表单不调用切换；过期版本、时间回拨和重复分心均应完整回滚。
        const auto version = current["version"].toString(); const auto pending = store.pendingCount();
        rejects([&] { store.distract("点饭", {}, start+1200000, "stale"); });
        rejects([&] { store.distract("点饭", {}, start-1, version); });
        ensure(store.pendingCount() == pending && store.active() == current, "failed transition changed state");
        store.distract(QStringLiteral("点饭"), QStringLiteral("生活"), start+1200000, version);
        current = store.active(); ensure(isDistracted(current) && current["id"] == id && taskSegments(current).size() == 2, "distraction lost task identity");
        ensure(focusDuration(current, start+1500000) == 1200000, "distraction counted as focus");
        rejects([&] { store.distract("nested", {}, start+1200001, current["version"].toString()); });
        rejects([&] { store.addLedger("locked"); });
    }
    {
        // 关闭并重新打开数据库后，恢复分心状态及原任务标签，再继续同一任务。
        Store store(profile); auto current = store.active(); ensure(isDistracted(current) && current["tag"] == "学习", "restart lost pending task");
        const auto version = current["version"].toString(); store.resumeFocus(start+1500000, version);
        rejects([&] { store.resumeFocus(start+1500000, version); });
        current = store.active(); ensure(!isDistracted(current) && current["task"] == "背单词" && current["tag"] == "学习", "original metadata lost");
        store.finish(start+2400000, current["version"].toString());
        auto record = store.entities("record")[0].toObject(); ensure(focusDuration(record, start+2400000) == 2100000, "expected 35 focus minutes");
        ensure(taskSegments(record).size() == 3 && store.entities("record").size() == 1, "task was split into separate records");
        // 手工修改不得重叠相邻区间；专注元数据统一更新，分心快照独立保留。
        rejects([&] { store.editSegment(id, 1, "bad", {}, start+1199999, start+1500000, record["version"].toString()); });
        store.editSegment(id, 0, QStringLiteral("复习单词"), QStringLiteral("学习"), start, start+1200000, record["version"].toString());
        record = store.entities("record")[0].toObject(); ensure(segmentRecord(record,2)["task"] == "复习单词" && segmentRecord(record,1)["task"] == "点饭", "segment metadata leaked");
        // 特殊字符在服务端转义后可能扩张；必须在本机拒绝，而不是进入永久失败的上传队列。
        auto large = record; QJsonArray largeParts;
        for (int i=0;i<18;++i) { QJsonObject part{{"kind",i%2 ? "distraction" : "focus"},{"started",start+i*1000},{"ended",start+(i+1)*1000}}; if (i%2) part["task"] = QString(10000,'&'); largeParts.append(part); }
        large["segments"] = largeParts; large["ended"] = start+18000;
        rejects([&] { validateRecord(large); });
        const auto backup = store.backup(); ensure(backup["formatVersion"] == 3, "wrong backup version");
        Store restored(QDir(directory).filePath("focus-restored")); restored.importBackup(backup);
        ensure(restored.importBackup(backup)["added"].toInt() == 0 && taskSegments(restored.entities("record")[0].toObject()).size() == 3, "backup lost segments");
        const auto csv = restored.csv(); ensure(csv.count("\r\n") == 4 && csv.contains("分心") && csv.contains("区间序号"), "CSV double counted aggregate");
        store.deleteRecord(id); ensure(store.entities("record").isEmpty() && taskSegments(store.entities("record",true)[0].toObject()).size() == 3, "delete lost task tombstone");
        // 再次分心允许空名称；结束全部保留最后的分心段，不自动制造新的专注区间。
        store.start(book, "再次记录"); current = store.active(); const auto next = current["started"].toInteger();
        store.distract("", {}, next+1000, current["version"].toString()); current = store.active();
        ensure(segmentRecord(current,1)["task"] == "分心", "default distraction name missing");
        store.resumeFocus(next+2000, current["version"].toString()); current = store.active();
        store.distract("第二次", {}, next+3000, current["version"].toString()); store.finish(next+4000);
        const auto ended = store.entities("record")[0].toObject(); ensure(ended["ended"].isDouble() && taskSegments(ended).size() == 4 && taskSegments(ended).last().toObject()["kind"] == "distraction", "finish all failed");
        // 普通旧记录从分段界面编辑仍保持单区间格式，不能生成非法的一项 segments 数组。
        Store legacy(QDir(directory).filePath("focus-legacy")); auto old = backup; old["formatVersion"] = 2;
        auto rows = old["records"].toArray(); auto row = rows[0].toObject(); row.remove("segments"); rows[0] = row; old["records"] = rows;
        legacy.importBackup(old); row = legacy.entities("record")[0].toObject();
        legacy.editSegment(id,0,"旧记录",{},start,start+2400000,row["version"].toString());
        ensure(!legacy.entities("record")[0].toObject().contains("segments"), "legacy edit created invalid segments");
    }
}
