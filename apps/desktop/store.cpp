#include "store.h"
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QJsonDocument>
#include <QSet>
#include <QSqlError>
#include <QSqlQuery>
#include <QUuid>
#include <QTimeZone>
#include <cmath>
#include <stdexcept>

void ensure(bool condition, const QString &message)
{
    if (!condition) throw std::runtime_error(message.toUtf8().constData());
}

/** 由数据库报告具体失败原因，SQL 值始终通过绑定传入。 */
static void execute(QSqlQuery &query) { ensure(query.exec(), query.lastError().text()); }
/** 每个实体和每次修改分别生成身份，重试不能复用成另一份内容。 */
static QString identity() { return QUuid::createUuid().toString(QUuid::WithoutBraces); }
/** 文件中的整数必须能被毫秒精度精确表示，不能把浮点数偷偷截断。 */
static bool integer(const QJsonValue &value)
{
    return value.isDouble() && std::isfinite(value.toDouble()) && std::floor(value.toDouble()) == value.toDouble();
}
/** 账本/事项长度限制用于拒绝明显损坏的文件，而不是截断用户内容。 */
static void validateEntity(const QJsonObject &entity)
{
    ensure(entity.value("id").isString() && !entity["id"].toString().isEmpty() && entity["id"].toString().size() <= 128,
           QStringLiteral("数据缺少有效编号"));
    ensure(entity["deleted"].isBool() && integer(entity["revision"]) && entity["revision"].toInteger() >= 1 && entity["revision"].toInteger() <= 9007199254740991LL,
           QStringLiteral("数据版本或删除状态无效"));
    ensure(!entity.contains("version") || (entity["version"].isString() && !entity["version"].toString().isEmpty() && entity["version"].toString().size() <= 128), QStringLiteral("版本编号无效"));
}
/** 导入内容只接受当前协议字段，避免本机接受后变成服务器永远拒绝的操作。 */
static void onlyFields(const QJsonObject &entity, const QStringList &allowed)
{
    for (auto it = entity.begin(); it != entity.end(); ++it) ensure(allowed.contains(it.key()), QStringLiteral("备份包含当前版本不支持的字段"));
}
void validateLedger(const QJsonObject &ledger)
{
    validateEntity(ledger); onlyFields(ledger, {"id", "name", "deleted", "revision", "version"});
    ensure(ledger["name"].isString() && !ledger["name"].toString().trimmed().isEmpty() && ledger["name"].toString().size() <= 200, QStringLiteral("备份账本名称无效"));
}
void validateRecord(const QJsonObject &record, bool allowRunning)
{
    validateEntity(record);
    onlyFields(record, {"id", "ledgerId", "task", "started", "ended", "deleted", "revision", "version"});
    ensure(record["task"].isString() && !record["task"].toString().trimmed().isEmpty() && record["task"].toString().size() <= 10000,
           QStringLiteral("事项不能为空，且不能超过一万字"));
    ensure(record["ledgerId"].isString() && !record["ledgerId"].toString().isEmpty() && record["ledgerId"].toString().size() <= 128, QStringLiteral("记录缺少有效账本编号"));
    ensure(integer(record["started"]) && record["started"].toInteger() >= 0 && record["started"].toInteger() < 253402271999000LL
           && ((allowRunning && record["ended"].isNull()) || (integer(record["ended"]) && record["ended"].toInteger() >= record["started"].toInteger() && record["ended"].toInteger() < 253402271999000LL)),
           QStringLiteral("起止时间无效；结束不能早于开始"));
}

Store::Store(const QString &directory)
{
    ensure(QDir().mkpath(directory), QStringLiteral("无法创建数据目录"));
    db = QSqlDatabase::addDatabase("QSQLITE", identity());
    db.setDatabaseName(QDir(directory).filePath("lyushi.sqlite"));
    ensure(db.open(), db.lastError().text());
    QSqlQuery query(db);
    for (const auto &sql : {
        "PRAGMA busy_timeout=5000", "PRAGMA journal_mode=WAL", "PRAGMA synchronous=FULL",
        "CREATE TABLE IF NOT EXISTS entities(kind TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))",
        "CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,payload TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,payload TEXT NOT NULL)",
        "DROP INDEX IF EXISTS one_active",
        "CREATE INDEX IF NOT EXISTS active_records ON entities(json_extract(payload,'$.started')) WHERE kind='record' AND json_extract(payload,'$.deleted')=0 AND json_extract(payload,'$.ended') IS NULL",
        "CREATE TABLE IF NOT EXISTS sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS sync_awaiting(version TEXT PRIMARY KEY,kind TEXT NOT NULL,id TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS sync_heads(kind TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id,version))"}) {
        ensure(query.exec(QString::fromUtf8(sql)), query.lastError().text());
    }
    // 旧版从未连接服务器：保留实体当前内容，将旧数值版本队列一次性转换为根操作。
    ensure(query.exec("SELECT value FROM sync_meta WHERE key='protocol'"), query.lastError().text());
    const bool initialized = query.next(); query.finish();
    if (!initialized) transaction([&] {
        QSqlQuery migration(db); ensure(migration.exec("DELETE FROM outbox"), migration.lastError().text());
        for (const auto &kind : {QString("ledger"), QString("record")}) for (const auto &value : entities(kind, true)) {
            auto entity = value.toObject(); entity.remove("version");
            migration.prepare("UPDATE entities SET payload=? WHERE kind=? AND id=?");
            migration.addBindValue(QJsonDocument(entity).toJson(QJsonDocument::Compact)); migration.addBindValue(kind); migration.addBindValue(entity["id"].toString()); execute(migration);
            save(kind, entity);
        }
        for (const auto &content : conflicts()) queueArchive(content.toObject());
        ensure(migration.exec("INSERT INTO sync_meta(key,value) VALUES('protocol','1')"), migration.lastError().text());
    });
}
/** 先关闭句柄再移除命名连接，避免同进程自检反复打开时遗留连接。 */
Store::~Store() { const auto name = db.connectionName(); db.close(); db = {}; QSqlDatabase::removeDatabase(name); }
void Store::transaction(const std::function<void()> &action)
{
    QSqlQuery begin(db);
    ensure(begin.exec("BEGIN IMMEDIATE"), begin.lastError().text());
    try { action(); ensure(db.commit(), db.lastError().text()); }
    catch (...) { db.rollback(); throw; }
}
QJsonObject Store::find(const QString &kind, const QString &id) const
{
    QSqlQuery query(db); query.prepare("SELECT payload FROM entities WHERE kind=? AND id=?");
    query.addBindValue(kind); query.addBindValue(id); execute(query);
    return query.next() ? QJsonDocument::fromJson(query.value(0).toByteArray()).object() : QJsonObject{};
}
QJsonArray Store::entities(const QString &kind, bool includeDeleted) const
{
    QSqlQuery query(db); query.prepare("SELECT payload FROM entities WHERE kind=? ORDER BY id");
    query.addBindValue(kind); execute(query);
    QJsonArray result;
    while (query.next()) {
        const auto entity = QJsonDocument::fromJson(query.value(0).toByteArray()).object();
        if (includeDeleted || !entity["deleted"].toBool()) result.append(entity);
    }
    return result;
}
/** 此内部函数只能在外层事务调用，实体与待上传变更不允许分开提交。 */
void Store::save(const QString &kind, QJsonObject entity)
{
    const auto previous = find(kind, entity["id"].toString());
    const auto operation = identity();
    QJsonArray parents; if (!previous["version"].toString().isEmpty()) parents.append(previous["version"]);
    // 用户明确删除时覆盖所有已知分支；尚未知晓的离线新分支仍会保留为冲突。
    if (entity["deleted"].toBool()) {
        QSqlQuery heads(db); heads.prepare("SELECT version FROM sync_heads WHERE kind=? AND id=?"); heads.addBindValue(kind); heads.addBindValue(entity["id"].toString()); execute(heads);
        while (heads.next()) if (!parents.contains(heads.value(0).toString())) parents.append(heads.value(0).toString());
    }
    entity["version"] = operation;
    entity["revision"] = previous.isEmpty() ? qMax<qint64>(1, entity["revision"].toInteger()) : previous["revision"].toInteger() + 1;
    const auto payload = QJsonDocument(entity).toJson(QJsonDocument::Compact);
    QSqlQuery query(db); query.prepare("INSERT OR REPLACE INTO entities(kind,id,payload) VALUES(?,?,?)");
    query.addBindValue(kind); query.addBindValue(entity["id"].toString()); query.addBindValue(payload); execute(query);
    query.prepare("INSERT INTO outbox(id,kind,entity_id,payload) VALUES(?,?,?,?)");
    query.addBindValue(operation); query.addBindValue(kind); query.addBindValue(entity["id"].toString());
    query.addBindValue(QJsonDocument(QJsonObject{{"parents", parents}, {"entity", entity}}).toJson(QJsonDocument::Compact));
    execute(query);
}
QJsonObject Store::active() const
{
    QSqlQuery query(db); ensure(query.exec("SELECT payload FROM entities WHERE kind='record' AND json_extract(payload,'$.deleted')=0 AND json_extract(payload,'$.ended') IS NULL ORDER BY json_extract(payload,'$.started') LIMIT 1"), query.lastError().text());
    return query.next() ? QJsonDocument::fromJson(query.value(0).toByteArray()).object() : QJsonObject{};
}
void Store::idle() const { ensure(active().isEmpty(), QStringLiteral("请先结束当前记录")); }
QString Store::addLedger(const QString &name)
{
    QString id = identity();
    transaction([&] { idle(); ensure(!name.trimmed().isEmpty() && name.size() <= 200, QStringLiteral("请输入不超过 200 字的账本名"));
        save("ledger", {{"id", id}, {"name", name.trimmed()}, {"deleted", false}, {"revision", 1}}); });
    return id;
}
void Store::renameLedger(const QString &id, const QString &name)
{
    transaction([&] { idle(); auto ledger = find("ledger", id);
        ensure(!ledger.isEmpty() && !ledger["deleted"].toBool(), QStringLiteral("账本不存在"));
        ensure(!name.trimmed().isEmpty() && name.size() <= 200, QStringLiteral("请输入不超过 200 字的账本名"));
        ledger["name"] = name.trimmed(); save("ledger", ledger); });
}
void Store::deleteLedger(const QString &id)
{
    transaction([&] { idle(); auto ledger = find("ledger", id); ensure(!ledger.isEmpty(), QStringLiteral("账本不存在"));
        for (const auto &value : entities("record")) { auto record = value.toObject(); if (record["ledgerId"].toString() == id) { record["deleted"] = true; save("record", record); } }
        ledger["deleted"] = true; save("ledger", ledger); });
}
void Store::start(const QString &ledgerId, const QString &task)
{
    transaction([&] { idle(); const auto ledger = find("ledger", ledgerId);
        ensure(!ledger.isEmpty() && !ledger["deleted"].toBool(), QStringLiteral("请先选择账本"));
        ensure(!task.trimmed().isEmpty() && task.size() <= 10000, QStringLiteral("请输入不超过一万字的事项"));
        save("record", {{"id", identity()}, {"ledgerId", ledgerId}, {"task", task.trimmed()},
             {"started", QDateTime::currentMSecsSinceEpoch()}, {"ended", QJsonValue::Null}, {"deleted", false}, {"revision", 1}}); });
}
void Store::finish(qint64 ended)
{
    transaction([&] { auto record = active(); ensure(!record.isEmpty(), QStringLiteral("没有进行中的记录"));
        record["ended"] = ended; validateRecord(record); save("record", record); });
}
void Store::editRecord(const QString &id, const QString &ledgerId, const QString &task, qint64 started, qint64 ended, const QString &expectedVersion)
{
    transaction([&] { idle(); const auto ledger = find("ledger", ledgerId);
        ensure(!ledger.isEmpty() && !ledger["deleted"].toBool(), QStringLiteral("账本不存在"));
        auto record = id.isEmpty() ? QJsonObject{{"id", identity()}, {"revision", 1}, {"deleted", false}} : find("record", id);
        ensure(!record.isEmpty() && !record["deleted"].toBool() && (id.isEmpty() || record["ledgerId"].toString() == ledgerId), QStringLiteral("记录不存在或不属于此账本"));
        ensure(expectedVersion.isEmpty() || record["version"].toString() == expectedVersion, QStringLiteral("记录已被另一设备更新，请保留输入并重新打开修改"));
        record["ledgerId"] = ledgerId; record["task"] = task.trimmed(); record["started"] = started; record["ended"] = ended;
        validateRecord(record); save("record", record); });
}
void Store::deleteRecord(const QString &id)
{
    transaction([&] { idle(); auto record = find("record", id); ensure(!record.isEmpty(), QStringLiteral("记录不存在"));
        record["deleted"] = true; save("record", record); });
}
QJsonObject Store::backup() const
{
    idle(); QJsonArray pending;
    for (const auto &value : conflicts()) pending.append(value);
    return {{"format", "lyushi.backup"}, {"formatVersion", 1}, {"ledgers", entities("ledger", true)},
        {"records", entities("record", true)}, {"conflicts", pending}};
}
QJsonArray Store::conflicts() const
{
    QSqlQuery query(db); ensure(query.exec("SELECT payload FROM conflicts ORDER BY id"), query.lastError().text());
    QJsonArray result; while (query.next()) result.append(QJsonDocument::fromJson(query.value(0).toByteArray()).object()); return result;
}
/**
 * 导入先完整校验再提交：编号相同而内容不同的版本单独保存，绝不覆盖本地或静默丢弃。
 * 冲突按内容散列去重并随备份带出；旧备份不能自动复活已删除实体。
 */
QJsonObject Store::importBackup(const QJsonObject &data)
{
    ensure(data["format"].toString() == "lyushi.backup" && data["formatVersion"].toInt() == 1
           && data["ledgers"].isArray() && data["records"].isArray() && data["conflicts"].isArray(), QStringLiteral("不支持的备份格式"));
    int added = 0, duplicate = 0, conflictCount = 0;
    transaction([&] {
        idle(); QSet<QString> ledgerIds, seen;
        for (const auto &value : data["ledgers"].toArray()) {
            const auto entity = value.toObject(); validateLedger(entity);
            ensure(!ledgerIds.contains(entity["id"].toString()), QStringLiteral("备份有重复账本编号")); ledgerIds.insert(entity["id"].toString());
        }
        for (const auto &value : data["records"].toArray()) {
            const auto entity = value.toObject(); validateRecord(entity);
            ensure(ledgerIds.contains(entity["ledgerId"].toString()), QStringLiteral("备份记录缺少所属账本"));
            ensure(!seen.contains(entity["id"].toString()), QStringLiteral("备份有重复记录编号")); seen.insert(entity["id"].toString());
        }
        auto preserve = [&](const QString &kind, const QJsonObject &entity, bool archived = false) {
            ensure(kind == "record" || kind == "ledger", QStringLiteral("冲突类型无效"));
            if (kind == "record") validateRecord(entity, archived); else validateLedger(entity);
            QJsonObject wrapped{{"kind", kind}, {"entity", entity}, {"archived", true}};
            const auto payload = QJsonDocument(wrapped).toJson(QJsonDocument::Compact);
            const auto hash = QCryptographicHash::hash(payload, QCryptographicHash::Sha256).toHex();
            QSqlQuery query(db); query.prepare("INSERT OR IGNORE INTO conflicts(id,kind,entity_id,payload) VALUES(?,?,?,?)");
            query.addBindValue(hash); query.addBindValue(kind); query.addBindValue(entity["id"].toString()); query.addBindValue(payload); execute(query);
            conflictCount += query.numRowsAffected();
            if (query.numRowsAffected() == 1) queueArchive(wrapped);
        };
        for (const auto &kind : {QString("ledger"), QString("record")}) {
            for (const auto &value : data[kind == "ledger" ? "ledgers" : "records"].toArray()) {
                const auto entity = value.toObject(); const auto local = find(kind, entity["id"].toString());
                const auto parent = kind == "record" ? find("ledger", entity["ledgerId"].toString()) : QJsonObject{};
                auto localContent = local, incomingContent = entity;
                for (const auto &field : {"version", "revision"}) { localContent.remove(field); incomingContent.remove(field); }
                if (localContent == incomingContent) ++duplicate;
                else if (local.isEmpty() && !(kind == "record" && parent["deleted"].toBool() && !entity["deleted"].toBool())) { save(kind, entity); ++added; }
                else preserve(kind, entity);
            }
        }
        for (const auto &value : data["conflicts"].toArray()) { const auto entry = value.toObject(); preserve(entry["kind"].toString(), entry["entity"].toObject(), entry["archived"].toBool()); }
    });
    return {{"added", added}, {"duplicate", duplicate}, {"conflicts", conflictCount}};
}
/** CSV 所有字段统一引用与转义；公式起始字符加引号前缀，避免表格误执行事项内容。 */
QString Store::csv(const QString &ledgerId) const
{
    idle(); auto quote = [](QString text) { if (!text.isEmpty() && QString("=+-@\t\r").contains(text.front())) text.prepend('\''); text.replace("\"", "\"\""); return "\"" + text + "\""; };
    auto date = [](qint64 ms) { return QDateTime::fromMSecsSinceEpoch(ms, QTimeZone::UTC).toOffsetFromUtc(28800).toString("yyyy-MM-dd HH:mm:ss.zzz"); };
    QString result = QString::fromUtf8("\xEF\xBB\xBF") + QStringLiteral("账本,事项,开始（北京时间）,结束（北京时间）,时长（秒）,记录编号\r\n");
    for (const auto &value : entities("record")) {
        const auto record = value.toObject(); if (!ledgerId.isEmpty() && record["ledgerId"].toString() != ledgerId) continue;
        const auto ledger = find("ledger", record["ledgerId"].toString()); if (ledger["deleted"].toBool()) continue;
        result += QStringList{quote(ledger["name"].toString()), quote(record["task"].toString()), quote(date(record["started"].toInteger())),
            quote(date(record["ended"].toInteger())), quote(QString::number((record["ended"].toInteger() - record["started"].toInteger()) / 1000.0, 'f', 3)), quote(record["id"].toString())}.join(',') + "\r\n";
    }
    return result;
}
