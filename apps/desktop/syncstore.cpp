/** 同步持久化边界：接受响应、移除已确认队列及推进游标使用同一 SQLite 事务。 */
#include "store.h"
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QSet>
#include <QSqlQuery>
#include <QSqlError>
#include <QUuid>

/** 不包含业务内容的读取辅助，数据库失败立即交给调用者提示。 */
static void run(QSqlQuery &query) { ensure(query.exec(), query.lastError().text()); }

/** 状态栏只检查存在性，避免每秒解析全部历史冲突内容。 */
bool Store::hasConflicts() const
{
    QSqlQuery query(db); ensure(query.exec("SELECT 1 FROM conflicts LIMIT 1"), query.lastError().text()); return query.next();
}

/** 导入的保留版本作为只读档案同步，不作为活动记录上传，避免旧备份恢复出新计时。 */
void Store::queueArchive(const QJsonObject &content)
{
    auto wrapped = content; wrapped["archived"] = true;
    const auto hash = QString::fromLatin1(QCryptographicHash::hash(QJsonDocument(wrapped).toJson(QJsonDocument::Compact), QCryptographicHash::Sha256).toHex());
    const auto version = QUuid::createUuid().toString(QUuid::WithoutBraces);
    const QJsonObject entity{{"id", hash}, {"version", version}, {"revision", 1}, {"deleted", false}, {"content", wrapped}};
    QSqlQuery query(db); query.prepare("INSERT INTO outbox(id,kind,entity_id,payload) VALUES(?,'archive',?,?)");
    query.addBindValue(version); query.addBindValue(hash); query.addBindValue(QJsonDocument(QJsonObject{{"parents", QJsonArray{}}, {"entity", entity}}).toJson(QJsonDocument::Compact)); run(query);
}

int Store::pendingCount() const
{
    QSqlQuery query(db); ensure(query.exec("SELECT COUNT(*) FROM outbox"), query.lastError().text()); ensure(query.next(), "cannot count operations"); return query.value(0).toInt();
}
QJsonObject Store::syncRequest() const
{
    QJsonObject result{{"serverId", ""}, {"cursor", 0}}; QSqlQuery query(db);
    ensure(query.exec("SELECT key,value FROM sync_meta WHERE key IN ('serverId','cursor')"), query.lastError().text());
    while (query.next()) result[query.value(0).toString()] = query.value(0).toString() == "cursor" ? QJsonValue(query.value(1).toLongLong()) : QJsonValue(query.value(1).toString());
    ensure(query.exec("SELECT id,kind,entity_id,payload FROM outbox ORDER BY rowid LIMIT 50"), query.lastError().text());
    QJsonArray operations; qint64 bytes = 0;
    while (query.next()) {
        const auto payload = QJsonDocument::fromJson(query.value(3).toByteArray()).object();
        const QJsonObject operation{{"id", query.value(0).toString()}, {"kind", query.value(1).toString()}, {"entityId", query.value(2).toString()},
            {"parents", payload["parents"].toArray()}, {"entity", payload["entity"]}};
        const auto size = QJsonDocument(operation).toJson(QJsonDocument::Compact).size() + 1;
        // 为信封字段留出余量；长文字、控制字符转义等也按真实 UTF-8 体积拆批。
        if (!operations.isEmpty() && bytes + size > 1536 * 1024) break;
        operations.append(operation); bytes += size;
    }
    result["operations"] = operations; return result;
}

/** 分支快照另存为只读冲突，并标记历史用途；备份恢复时不把历史活动重新开始。 */
void Store::preserveRemote(const QString &kind, const QJsonObject &entity)
{
    const QJsonObject wrapped{{"kind", kind}, {"entity", entity}, {"archived", true}};
    const auto payload = QJsonDocument(wrapped).toJson(QJsonDocument::Compact);
    const auto hash = QCryptographicHash::hash(payload, QCryptographicHash::Sha256).toHex();
    QSqlQuery query(db); query.prepare("INSERT OR IGNORE INTO conflicts(id,kind,entity_id,payload) VALUES(?,?,?,?)");
    query.addBindValue(hash); query.addBindValue(kind); query.addBindValue(entity["id"].toString()); query.addBindValue(payload); run(query);
}

bool Store::acceptSync(const QJsonObject &response, const QJsonArray &sent)
{
    const auto before = syncRequest(); const auto server = response["serverId"].toString();
    ensure(!server.isEmpty() && (before["serverId"].toString().isEmpty() || before["serverId"] == response["serverId"]), QStringLiteral("服务器身份变化，已保护本机数据，请核对连接地址"));
    ensure(response["ack"].isArray() && response["changes"].isArray() && response["more"].isBool() && response["cursor"].isDouble(), QStringLiteral("同步响应格式无效"));
    QSet<QString> expected, acknowledged;
    for (const auto &entry : sent) expected.insert(entry.toObject()["id"].toString());
    for (const auto &entry : response["ack"].toArray()) acknowledged.insert(entry.toString());
    ensure(expected == acknowledged && response["ack"].toArray().size() == acknowledged.size(), QStringLiteral("服务器没有确认完整批次，本机队列保留"));
    // 已建立身份且没有任何变化时不写数据库，避免空闲轮询反复刷盘。
    if (sent.isEmpty() && response["changes"].toArray().isEmpty() && !response["more"].toBool()
        && before["serverId"] == response["serverId"] && before["cursor"] == response["cursor"]) return false;
    bool changed = false;
    transaction([&] {
        QSqlQuery query(db);
        for (const auto &value : sent) {
            const auto op = value.toObject();
            query.prepare("INSERT OR IGNORE INTO sync_awaiting(version,kind,id) VALUES(?,?,?)"); query.addBindValue(op["id"].toString()); query.addBindValue(op["kind"].toString()); query.addBindValue(op["entityId"].toString()); run(query);
            query.prepare("DELETE FROM outbox WHERE id=?"); query.addBindValue(op["id"].toString()); run(query);
        }
        qint64 cursor = before["cursor"].toInteger(); QSet<QString> touched;
        for (const auto &value : response["changes"].toArray()) {
            const auto change = value.toObject(); const auto kind = change["kind"].toString(), id = change["id"].toString();
            ensure((kind == "ledger" || kind == "record" || kind == "archive") && !id.isEmpty() && change["seq"].toInteger() > cursor && change["heads"].isArray() && !change["heads"].toArray().isEmpty(), QStringLiteral("同步变更无效"));
            cursor = change["seq"].toInteger(); touched.insert(kind + ":" + id);
            query.prepare("DELETE FROM sync_heads WHERE kind=? AND id=?"); query.addBindValue(kind); query.addBindValue(id); run(query);
            const auto heads = change["heads"].toArray();
            for (const auto &head : heads) {
                const auto entity = head.toObject(); ensure(entity["id"].toString() == id && !entity["version"].toString().isEmpty() && entity["deleted"].isBool(), QStringLiteral("同步版本无效"));
                query.prepare("DELETE FROM sync_awaiting WHERE version=?"); query.addBindValue(entity["version"].toString()); run(query);
                if (kind == "archive") {
                    auto content = entity["content"].toObject(); const auto original = content["entity"].toObject(); const auto originalKind = content["kind"].toString();
                    ensure(originalKind == "ledger" || originalKind == "record", QStringLiteral("同步档案类型无效"));
                    if (originalKind == "record") validateRecord(original, true); else ensure(!original["name"].toString().trimmed().isEmpty(), QStringLiteral("同步档案名称无效"));
                    content["archived"] = true;
                    query.prepare("INSERT OR IGNORE INTO conflicts(id,kind,entity_id,payload) VALUES(?,?,?,?)"); query.addBindValue(id); query.addBindValue(originalKind); query.addBindValue(original["id"].toString()); query.addBindValue(QJsonDocument(content).toJson(QJsonDocument::Compact)); run(query);
                }
                else if (kind == "record") { auto completed = entity; if (completed["ended"].isNull()) completed["ended"] = completed["started"]; validateRecord(completed); }
                else ensure(!entity["name"].toString().trimmed().isEmpty(), QStringLiteral("同步账本名无效"));
                query.prepare("INSERT INTO sync_heads(kind,id,version,payload) VALUES(?,?,?,?)"); query.addBindValue(kind); query.addBindValue(id); query.addBindValue(entity["version"].toString()); query.addBindValue(QJsonDocument(entity).toJson(QJsonDocument::Compact)); run(query);
                if (heads.size() > 1 && kind != "archive") preserveRemote(kind, entity);
            }
        }
        ensure(response["cursor"].toInteger() == cursor, QStringLiteral("同步游标不匹配"));
        // 先收齐本页的最终状态。仍有本地未确认修改的实体保持界面内容，下一批再应用。
        for (const auto &key : touched) {
            const auto split = key.indexOf(':'); const auto kind = key.left(split), id = key.mid(split + 1);
            query.prepare("SELECT (SELECT COUNT(*) FROM outbox WHERE kind=? AND entity_id=?) + (SELECT COUNT(*) FROM sync_awaiting WHERE kind=? AND id=?)"); query.addBindValue(kind); query.addBindValue(id); query.addBindValue(kind); query.addBindValue(id); run(query); query.next(); const bool pending = query.value(0).toInt() > 0; query.finish(); if (pending) continue;
            query.prepare("SELECT payload FROM sync_heads WHERE kind=? AND id=? ORDER BY version"); query.addBindValue(kind); query.addBindValue(id); run(query);
            QJsonArray heads; while (query.next()) heads.append(QJsonDocument::fromJson(query.value(0).toByteArray()).object());
            const auto current = find(kind, id); QJsonObject chosen;
            auto priority = [&](const QJsonObject &entity) { return (!entity["deleted"].toBool() ? 4 : 0) + (kind == "record" && entity["ended"].isNull() ? 8 : 0) + (entity["version"] == current["version"] ? 1 : 0); };
            for (const auto &head : heads) if (chosen.isEmpty() || priority(head.toObject()) > priority(chosen)) chosen = head.toObject();
            if (chosen != current) { changed = true; query.prepare("INSERT OR REPLACE INTO entities(kind,id,payload) VALUES(?,?,?)"); query.addBindValue(kind); query.addBindValue(id); query.addBindValue(QJsonDocument(chosen).toJson(QJsonDocument::Compact)); run(query); }
        }
        // 整本删除之后到达的离线记录仍然可从冲突档案查看。
        if (!touched.isEmpty()) {
            ensure(query.exec("SELECT r.payload FROM entities r LEFT JOIN entities b ON b.kind='ledger' AND b.id=json_extract(r.payload,'$.ledgerId') WHERE r.kind='record' AND json_extract(r.payload,'$.deleted')=0 AND (b.id IS NULL OR json_extract(b.payload,'$.deleted')=1)"), query.lastError().text());
            QJsonArray orphans; while (query.next()) orphans.append(QJsonDocument::fromJson(query.value(0).toByteArray()).object());
            for (const auto &orphan : orphans) preserveRemote("record", orphan.toObject());
        }
        for (const auto &entry : {qMakePair(QString("serverId"), server), qMakePair(QString("cursor"), QString::number(cursor))}) {
            query.prepare("INSERT OR REPLACE INTO sync_meta(key,value) VALUES(?,?)"); query.addBindValue(entry.first); query.addBindValue(entry.second); run(query);
        }
    }); return changed;
}
