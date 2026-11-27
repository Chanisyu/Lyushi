#include "store.h"
#include <QDateTime>
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSqlError>
#include <QSqlQuery>
#include <QUuid>

namespace {
constexpr int MaxML = 10000;
constexpr qint64 MaxTime = 253402271999000LL;
const auto changed = QStringLiteral("饮水状态已更新，请核对后重新操作");

bool drinking(const QString &kind) { return kind == "drink" || kind == "manual"; }
bool entryKind(const QString &kind) { return drinking(kind) || kind == "fill" || kind == "pour"; }
QString identifier() { return QUuid::createUuid().toString(QUuid::WithoutBraces); }
bool validId(const QString &id) { static const QRegularExpression pattern("^[a-f0-9-]{32,64}$"); return pattern.match(id).hasMatch(); }

/** 服务器回执给出已提交投影。客户端按相同顺序叠加未同步操作，供离线使用。 */
void applyChange(QJsonObject &data, const QJsonObject &change)
{
    if (change["bottle"].isObject()) data["bottle"] = change["bottle"];
    if (change["entry"].isObject()) {
        auto entries = data["entries"].toObject();
        const auto entry = change["entry"].toObject();
        entries[entry["id"].toString()] = entry;
        data["entries"] = entries;
    }
    if (change.contains("resolved") || change.contains("conflict")) {
        auto conflicts = data["conflicts"].toObject();
        conflicts.remove(change["resolved"].toString());
        if (change.contains("conflict")) conflicts[change["command"].toObject()["id"].toString()] = change;
        data["conflicts"] = conflicts;
    }
}

/** 生成离线预览，提交后仍由服务器依据同一前置版本裁决。更正历史不改变水壶。 */
QJsonObject preview(const QJsonObject &data, const QJsonObject &c)
{
    const auto id = c["id"].toString(), kind = c["kind"].toString(), target = c["target"].toString();
    const auto at = c["at"].toInteger();
    const int ml = c["ml"].toInt(-1), requestedCapacity = c["capacity"].toInt();
    ensure(validId(id) && at > 0 && at < MaxTime, QStringLiteral("饮水记录编号或时间无效"));
    auto makeEntry = [&](const QString &entryId, const QString &type, int amount, qint64 when, bool voided) {
        ensure(amount > 0 && amount <= MaxML && entryKind(type), QStringLiteral("请输入 1 至 10000 mL"));
        return QJsonObject{{"id",entryId},{"kind",type},{"ml",amount},{"at",when},{"version",id},{"voided",voided}};
    };
    QJsonObject result;
    const auto entries = data["entries"].toObject();
    if (kind == "manual") return {{"entry",makeEntry(id,kind,ml,at,false)}};
    if (kind == "restore-entry") {
        ensure(validId(target), QStringLiteral("备份记录编号无效"));
        const auto existing = entries[target].toObject();
        const auto entry = makeEntry(target,c["entry"].toString(),ml,at,c["voided"].toBool());
        if (!existing.isEmpty()) {
            auto sameVersion = entry;
            sameVersion["version"] = existing["version"];
            if (existing == sameVersion) return {};
            return {{"command",c},{"conflict","history_differs"}};
        }
        return {{"entry",entry}};
    }
    if (kind == "correct" || kind == "void") {
        auto entry = entries[target].toObject();
        ensure(!entry.isEmpty() && !entry["voided"].toBool() && drinking(entry["kind"].toString()) && entry["version"] == c["base"],changed);
        if (kind == "void") entry["voided"] = true;
        else entry = makeEntry(target,entry["kind"].toString(),ml,at,false);
        entry["version"] = id;
        return {{"entry",entry}};
    }
    if (kind == "dismiss" || kind == "recover") {
        const auto conflict = data["conflicts"].toObject()[target].toObject()["command"].toObject();
        ensure(!conflict.isEmpty(),changed);
        result["resolved"] = target;
        if (kind == "recover") {
            ensure(conflict["kind"] == "drink", QStringLiteral("仅喝水操作可转为补记"));
            result["entry"] = makeEntry(id,"manual",conflict["from"].toInt()-conflict["ml"].toInt(),conflict["at"].toInteger(),false);
        }
        return result;
    }
    const auto bottle = data["bottle"].toObject();
    const int capacity = bottle["capacity"].toInt(), before = bottle["ml"].toInt();
    const bool setup = kind == "setup" || kind == "restore-bottle";
    auto validateCapacity = [&] { ensure(requestedCapacity > 0 && requestedCapacity <= MaxML, QStringLiteral("请输入 1 至 10000 mL 的容量")); };
    if (setup) {
        validateCapacity();
        ensure(bottle.isEmpty() && ml >= 0 && ml <= requestedCapacity && (kind != "setup" || ml == 0),changed);
        return {{"bottle",QJsonObject{{"capacity",requestedCapacity},{"ml",ml},{"version",id}}}};
    }
    ensure(!bottle.isEmpty() && bottle["version"] == c["base"],changed);
    QJsonObject next{{"capacity",capacity},{"ml",before},{"version",id}};
    QJsonObject undo{{"capacity",capacity},{"ml",before}};
    if (kind == "capacity") {
        validateCapacity();
        ensure(requestedCapacity >= before, QStringLiteral("请先喝水或倒水，使剩余量低于新容量"));
        if (requestedCapacity == capacity) return {};
        next["capacity"] = requestedCapacity;
        next["undo"] = undo;
    } else if (kind == "calibrate") {
        ensure(ml >= 0 && ml <= capacity,QStringLiteral("剩余水量不能超过水壶容量"));
        if (ml == before) return {};
        next["ml"] = ml;
        next["undo"] = undo;
    } else if (kind == "undo") {
        const auto prior = bottle["undo"].toObject();
        ensure(!prior.isEmpty(), QStringLiteral("没有可撤销的水壶操作"));
        next["capacity"] = prior["capacity"]; next["ml"] = prior["ml"];
        if (prior.contains("entry")) {
            auto entry = entries[prior["entry"].toString()].toObject();
            ensure(!entry.isEmpty(), QStringLiteral("撤销缺少对应明细"));
            entry["voided"] = true; entry["version"] = id; result["entry"] = entry;
        }
    } else {
        ensure(ml >= 0 && ml <= capacity && c["from"].toInt() == before &&
               ((kind == "fill" && ml > before) || ((kind == "drink" || kind == "pour") && ml < before)),changed);
        next["ml"] = ml;
        undo["entry"] = id; next["undo"] = undo;
        result["entry"] = makeEntry(id,kind,qAbs(ml-before),at,false);
    }
    result["bottle"] = next;
    return result;
}

QJsonObject visibleWater(QJsonObject data)
{
    for (const auto &value : data["pending"].toArray()) {
        // 远端先更新时，过期的本地预览暂时退出累计，原操作继续等待服务器登记。
        try { applyChange(data,preview(data,value.toObject())); } catch (const std::exception &) {}
    }
    return data;
}

QJsonObject command(QString kind, QJsonObject fields = {})
{
    fields["kind"] = kind;
    fields["id"] = identifier();
    if (!fields.contains("at")) fields["at"] = QDateTime::currentMSecsSinceEpoch();
    return fields;
}

/**
 * 旧版本可能把备份扩展字段放进恢复水壶命令，服务端会因未知字段拒绝整批。
 * 发送时仅剔除协议从未接收的字段，保留原操作编号、时间和已知字段原值。
 * 已确认但回执未到的合法命令仍可按原内容重试，后续依赖该编号的操作也无需重写。
 */
QJsonObject waterWireCommand(const QJsonObject &operation)
{
    if (operation["kind"] != "restore-bottle") return operation;
    static const QSet<QString> protocolFields{"id","kind","at","base","target","ml","from","capacity","entry","voided"};
    QJsonObject result;
    for (auto field = operation.begin(); field != operation.end(); ++field) {
        // 服务端 JSON 解码允许字段名大小写变化，因此必须保留旧队列中有效的别名。
        if (protocolFields.contains(field.key().toCaseFolded())) result.insert(field.key(), field.value());
    }
    return result;
}

/** 相同备份内容跨端生成同一编号，重复导入即使存在差异也只保留一份核对记录。 */
QString restoreId(const QJsonObject &c)
{
    const auto identity = QStringList{QStringLiteral("lyushi-water-restore-entry-v1"),c["target"].toString(),c["entry"].toString(),
        QString::number(c["at"].toInteger()),QString::number(c["ml"].toInt()),c["voided"].toBool()?QStringLiteral("1"):QStringLiteral("0")}.join('\n');
    return QString::fromLatin1(QCryptographicHash::hash(identity.toUtf8(),QCryptographicHash::Sha256).toHex());
}
}

QJsonObject Store::waterData() const
{
    QSqlQuery query(db);
    ensure(query.exec("SELECT payload FROM water_local WHERE singleton=1"),query.lastError().text());
    if (!query.next()) return {};
    const auto document = QJsonDocument::fromJson(query.value(0).toByteArray());
    ensure(document.isObject(),QStringLiteral("本地饮水数据无法读取"));
    return document.object();
}

void Store::saveWater(const QJsonObject &data)
{
    QSqlQuery query(db);
    query.prepare("INSERT OR REPLACE INTO water_local VALUES(1,?)");
    query.addBindValue(QJsonDocument(data).toJson(QJsonDocument::Compact));
    ensure(query.exec(),query.lastError().text());
}

QJsonObject Store::waterView() const { return visibleWater(waterData()); }

bool Store::waterIsBound() const
{
    QSqlQuery query(db);
    ensure(query.exec("SELECT 1 FROM sync_meta WHERE key='boundServerId'"),query.lastError().text());
    return query.next();
}

void Store::waterSubmit(QJsonObject fields)
{
    transaction([&] {
        auto data = waterData();
        const auto c = command(fields["kind"].toString(),fields);
        const auto change = preview(visibleWater(data),c);
        if (change.isEmpty()) return;
        if (waterIsBound()) {
            auto pending = data["pending"].toArray();
            pending.append(c);
            data["pending"] = pending;
        } else applyChange(data,change);
        saveWater(data);
    });
}

QJsonObject Store::waterRequest(const QString &serverId) const
{
    const auto data = waterData(); QJsonArray operations;
    for (const auto &op : data["pending"].toArray()) {
        if (operations.size() == 50) break;
        operations.append(waterWireCommand(op.toObject()));
    }
    return {{"serverId",serverId},{"cursor",data["cursor"].toInteger()},{"operations",operations}};
}

bool Store::waterAccept(const QString &serverId, const QJsonObject &reply)
{
    bool advanced = false;
    transaction([&] {
        auto data = waterData(); auto cursor = data["cursor"].toInteger();
        ensure(reply["serverId"] == serverId && reply["changes"].isArray() && reply["more"].isBool() && reply["ack"].isObject(),QStringLiteral("饮水服务器响应无效"));
        QSet<QString> received;
        for (const auto &item : reply["changes"].toArray()) {
            const auto change = item.toObject(); const auto id = change["command"].toObject()["id"].toString();
            ensure(change["seq"].toInteger() == ++cursor && validId(id),QStringLiteral("饮水同步顺序无效"));
            received.insert(id); applyChange(data,change);
        }
        ensure(reply["cursor"].toInteger(-1) == cursor,QStringLiteral("饮水游标无效"));
        const auto acknowledgements = reply["ack"].toObject();
        for (auto it=acknowledgements.begin(); it!=acknowledgements.end(); ++it) {
            const auto seq=it.value().toInteger();
            ensure(validId(it.key()) && seq>0,QStringLiteral("饮水确认编号无效"));
            // 若回执已在旧分页出现，可清除同号重试；未看到的未来分页仍保留操作。
            if (seq<=cursor) received.insert(it.key());
        }
        if (received.isEmpty()) return;
        QJsonArray remaining;
        for (const auto &item : data["pending"].toArray()) if (!received.contains(item.toObject()["id"].toString())) remaining.append(item);
        data["pending"] = remaining; data["cursor"] = cursor;
        saveWater(data); advanced = true;
    });
    return advanced;
}

QJsonObject Store::waterBackup() const
{
    const auto data = waterView(); QJsonArray entries;
    for (const auto &entry : data["entries"].toObject()) entries.append(entry);
    const auto bottle = data["bottle"].toObject();
    return {{"format","lyushi.water.backup"},{"version",1},{"bottle",bottle.isEmpty() ? QJsonObject{} : QJsonObject{{"capacity",bottle["capacity"]},{"ml",bottle["ml"]}}},{"entries",entries}};
}

/** 合并只恢复缺少的历史，已初始化水壶继续按实物管理，同号异文进入核对。 */
void Store::waterImport(const QJsonObject &backup)
{
    ensure(backup["format"] == "lyushi.water.backup" && backup["version"] == 1 && backup["entries"].isArray() && backup["bottle"].isObject(),QStringLiteral("饮水备份格式无效"));
    transaction([&] {
        auto data = waterData(); auto visible = visibleWater(data); auto pending = data["pending"].toArray();
        const bool bound = waterIsBound();
        QSet<QString> queued;
        for (const auto &value : pending) queued.insert(value.toObject()["id"].toString());
        auto restoreChange = [&](const QJsonObject &op,const QJsonObject &change) {
            if (change.isEmpty() || queued.contains(op["id"].toString())) return;
            if (bound) pending.append(op); else applyChange(data,change);
        };
        const auto initial = visible["entries"].toObject(); const auto bottle = backup["bottle"].toObject();
        if (!bottle.isEmpty()) {
            // 备份格式可携带额外元数据，恢复只需要已知容量与实际剩余量。
            const auto restore = command("restore-bottle", {{"capacity",bottle["capacity"]},{"ml",bottle["ml"]}});
            const auto result = preview({},restore);
            if (visible["bottle"].toObject().isEmpty()) restoreChange(restore,result);
        }
        QSet<QString> seen;
        for (const auto &value : backup["entries"].toArray()) {
            const auto entry = value.toObject(); const auto id = entry["id"].toString();
            ensure(validId(id) && !seen.contains(id) && entry["at"].isDouble() && entry["ml"].isDouble() && entry["voided"].isBool(),QStringLiteral("饮水备份明细无效或重复"));
            seen.insert(id);
            auto restore = command("restore-entry",{{"at",entry["at"]},{"target",id},{"entry",entry["kind"]},{"ml",entry["ml"]},{"voided",entry["voided"]}});
            restore["id"] = restoreId(restore);
            // 每个编号在备份中只出现一次，从初始投影检测重复，避免逐条复制全部历史。
            restoreChange(restore,preview(QJsonObject{{"entries",initial}},restore));
        }
        data["pending"] = pending; saveWater(data);
    });
}
