#include "store.h"
#include <QJsonDocument>

/** Go JSON 会转义 HTML 字符和行分隔符；按同一规则预留同步大小，结果不写入记录正文。 */
qint64 taskPayloadSize(const QJsonObject &record)
{
    auto bytes = QJsonDocument(record).toJson(QJsonDocument::Compact);
    bytes.replace("<", "\\u003c"); bytes.replace(">", "\\u003e"); bytes.replace("&", "\\u0026");
    bytes.replace(QByteArray::fromHex("e280a8"), "\\u2028"); bytes.replace(QByteArray::fromHex("e280a9"), "\\u2029");
    return bytes.size();
}

/** 旧记录按单个专注区间解释；首次分心才持久化分段，不改写历史操作。 */
QJsonArray taskSegments(const QJsonObject &record)
{
    if (record.contains("segments")) return record["segments"].toArray();
    return QJsonArray{QJsonObject{{"kind", "focus"}, {"started", record["started"]}, {"ended", record["ended"]}}};
}
/** 当前分心属于同一个任务聚合，结束、恢复与同步均不生成第二个活动任务。 */
bool isDistracted(const QJsonObject &record)
{
    const auto parts = taskSegments(record);
    return !record.isEmpty() && record["ended"].isNull() && !parts.isEmpty() && parts.last().toObject()["kind"] == "distraction";
}
/** 聚合有效时长只累计专注区间，不合计其他冲突分支或分心区间。 */
qint64 focusDuration(const QJsonObject &record, qint64 now)
{
    qint64 total = 0;
    for (const auto &value : taskSegments(record)) { const auto part = value.toObject();
        if (part["kind"] == "focus") total += qMax<qint64>(0, (part["ended"].isNull() ? now : part["ended"].toInteger()) - part["started"].toInteger()); }
    return total;
}
/** 展示区间采用当次分心快照或主任务元数据，专注段改名不会影响分心事项。 */
QJsonObject segmentRecord(const QJsonObject &record, int index)
{
    const auto part = taskSegments(record).at(index).toObject(); auto result = record;
    result["started"] = part["started"]; result["ended"] = part["ended"]; result.remove("segments");
    if (part["kind"] == "distraction") { result["task"] = part["task"]; result.remove("tag"); if (part.contains("tag")) result["tag"] = part["tag"]; }
    return result;
}
/** 以读到的版本为前置条件，拒绝另一设备改变状态后提交的旧表单或重复点击。 */
void Store::distract(const QString &task, const QString &tag, qint64 now, const QString &expectedVersion)
{
    transaction([&] { auto record = active();
        ensure(!record.isEmpty() && !isDistracted(record), QStringLiteral("当前无法再次分心，请先返回原任务"));
        ensure(record["version"].toString() == expectedVersion, QStringLiteral("任务已由其他设备更新，请核对后重试"));
        bool validTag = tag.isEmpty(); for (const auto &value : entities("tag")) if (value.toObject()["name"].toString() == tag) validTag = true;
        ensure(validTag, QStringLiteral("标签已被删除，请重新选择"));
        auto parts = taskSegments(record); auto last = parts.last().toObject(); last["ended"] = now; parts[parts.size()-1] = last;
        QJsonObject next{{"kind", "distraction"}, {"task", task.trimmed().isEmpty() ? QStringLiteral("分心") : task.trimmed()}, {"started", now}, {"ended", QJsonValue::Null}};
        if (!tag.isEmpty()) next["tag"] = tag;
        parts.append(next); record["segments"] = parts;
        // 为结束时间、修订号和版本外壳预留空间，达到阈值后仍可结束整个任务。
        ensure(taskPayloadSize(record) <= 500 * 1024, QStringLiteral("任务分段数据已达上限，请结束后新建任务"));
        validateRecord(record, true); save("record", record);
    });
}
/** 在一次事务和一个版本中结束分心并恢复专注，切换边界使用同一个毫秒时间点。 */
void Store::resumeFocus(qint64 now, const QString &expectedVersion)
{
    transaction([&] { auto record = active(); ensure(isDistracted(record), QStringLiteral("当前没有可结束的分心"));
        ensure(record["version"].toString() == expectedVersion, QStringLiteral("任务已由其他设备更新，请核对后重试"));
        auto parts = taskSegments(record); auto last = parts.last().toObject(); last["ended"] = now; parts[parts.size()-1] = last;
        parts.append(QJsonObject{{"kind", "focus"}, {"started", now}, {"ended", QJsonValue::Null}});
        record["segments"] = parts;
        // 为结束时间、修订号和版本外壳预留空间，达到阈值后仍可结束整个任务。
        ensure(taskPayloadSize(record) <= 500 * 1024, QStringLiteral("任务分段数据已达上限，请结束后新建任务"));
        validateRecord(record, true); save("record", record);
    });
}
/**
 * 记录期间只更正元数据，不重建区间、不读取当前时刻，也不改变任务归属。
 * 专注区间共用根事项；当前分心仅修改最后一段，已结束的分心快照保持原样。
 * 校验与保存处于同一事务，失败时实体和上传队列同时回滚；无变化不制造新版本。
 */
void Store::editActiveMetadata(const QString &id, const QString &expectedVersion, const QString &task, const QString &tag)
{
    transaction([&] {
        auto record = active();
        ensure(!id.isEmpty() && !expectedVersion.isEmpty() && record["id"].toString() == id && record["version"].toString() == expectedVersion,
            QStringLiteral("任务状态已更新，输入已保留。请核对主页面，取消后重新打开修改"));
        const auto before = record;
        auto parts = taskSegments(record);
        const bool distracted = isDistracted(record);
        auto target = distracted ? parts.last().toObject() : record;
        bool validTag = tag.isEmpty() || target["tag"].toString() == tag;
        for (const auto &value : entities("tag")) if (value.toObject()["name"].toString() == tag) validTag = true;
        ensure(validTag, QStringLiteral("标签已被删除，请重新选择；输入已保留"));
        target["task"] = task.trimmed();
        if (tag.isEmpty()) target.remove("tag"); else target["tag"] = tag;
        if (distracted) { parts[parts.size()-1] = target; record["segments"] = parts; }
        else record = target;
        validateRecord(record, true);
        // 与阶段切换一致预留结束字段空间，避免改名后只能保存却不能结束任务。
        ensure(taskPayloadSize(record) <= 500 * 1024, QStringLiteral("任务数据已达上限，请缩短事项名称"));
        if (record != before) save("record", record);
    });
}
/** 已结束任务的时间按区间修改，允许空隙但不允许逆序、重叠或重启计时。 */
void Store::editSegment(const QString &id, int index, const QString &task, const QString &tag, qint64 started, qint64 ended, const QString &expectedVersion)
{
    transaction([&] { idle(); auto record = find("record", id);
        ensure(!record.isEmpty() && !record["deleted"].toBool() && !record["ended"].isNull(), QStringLiteral("任务不存在或尚未结束"));
        ensure(record["version"].toString() == expectedVersion, QStringLiteral("记录已更新，请重新打开分段明细"));
        auto parts = taskSegments(record); ensure(index >= 0 && index < parts.size(), QStringLiteral("区间不存在"));
        auto part = parts[index].toObject(); part["started"] = started; part["ended"] = ended;
        if (part["kind"] == "distraction") { part["task"] = task.trimmed(); if (tag.isEmpty()) part.remove("tag"); else part["tag"] = tag; }
        else { record["task"] = task.trimmed(); if (tag.isEmpty()) record.remove("tag"); else record["tag"] = tag; }
        parts[index] = part; if (parts.size() > 1) record["segments"] = parts;
        record["started"] = parts.first().toObject()["started"]; record["ended"] = parts.last().toObject()["ended"];
        validateRecord(record); save("record", record);
    });
}
