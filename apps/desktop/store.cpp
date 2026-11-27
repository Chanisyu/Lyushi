#include "store.h"
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QMap>
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
    onlyFields(record, {"id", "ledgerId", "task", "started", "ended", "deleted", "revision", "version", "tag", "segments"});
    // 无标签的旧记录不添加空字段；已有快照必须是有界字符串。
    ensure(!record.contains("tag") || (record["tag"].isString() && !record["tag"].toString().trimmed().isEmpty() && record["tag"].toString().size() <= 200), QStringLiteral("任务标签无效"));
    ensure(record["task"].isString() && !record["task"].toString().trimmed().isEmpty() && record["task"].toString().size() <= 10000,
           QStringLiteral("事项不能为空，且不能超过一万字"));
    ensure(record["ledgerId"].isString() && !record["ledgerId"].toString().isEmpty() && record["ledgerId"].toString().size() <= 128, QStringLiteral("记录缺少有效账本编号"));
    ensure(integer(record["started"]) && record["started"].toInteger() >= 0 && record["started"].toInteger() < 253402271999000LL
           && ((allowRunning && record["ended"].isNull()) || (integer(record["ended"]) && record["ended"].toInteger() >= record["started"].toInteger() && record["ended"].toInteger() < 253402271999000LL)),
           QStringLiteral("起止时间无效；结束不能早于开始"));
    if (record.contains("segments")) {
        ensure(record["segments"].isArray(), QStringLiteral("分段格式无效")); const auto parts = record["segments"].toArray();
        ensure(parts.size() >= 2 && parts.size() <= 256 && taskPayloadSize(record) <= 512 * 1024, QStringLiteral("任务分段超过上限，请结束后新建任务"));
        qint64 previousEnd = record["started"].toInteger();
        for (int i = 0; i < parts.size(); ++i) {
            ensure(parts[i].isObject(), QStringLiteral("分段必须是对象")); const auto part = parts[i].toObject(); const bool distraction = i % 2 == 1;
            onlyFields(part, distraction ? QStringList{"kind","started","ended","task","tag"} : QStringList{"kind","started","ended"});
            ensure(part["kind"] == (distraction ? "distraction" : "focus"), QStringLiteral("分心仅支持一层且必须与专注交替"));
            auto entry = segmentRecord(record, i); validateRecord(entry, allowRunning && i == parts.size()-1);
            ensure(entry["started"].toInteger() >= previousEnd && (i != 0 || entry["started"] == record["started"]), QStringLiteral("分段时间重叠或顺序无效"));
            previousEnd = entry["ended"].toInteger();
        }
        ensure(parts.last().toObject()["ended"] == record["ended"], QStringLiteral("任务结束时间与最后区间不一致"));
    }
}

Store::Store(const QString &directory) { openDirectory(directory); }
/** 连接重建集中在同一入口，原目录和新目录分别保留其队列、游标与冲突。 */
void Store::openDirectory(const QString &directory)
{
    ensure(QDir().mkpath(directory), QStringLiteral("无法创建数据目录"));
    const auto lockPath=QDir(QFileInfo(directory).absoluteFilePath()).filePath("store.lock");std::unique_ptr<QLockFile> nextLock;
    if(!profileLock || profileLock->fileName()!=lockPath){ nextLock=std::make_unique<QLockFile>(lockPath);ensure(nextLock->tryLock(0),QStringLiteral("此账户数据空间已由另一个进程打开")); }
    if(db.isValid()){const auto name=db.connectionName();db.close();db={};QSqlDatabase::removeDatabase(name);}
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
        "CREATE TABLE IF NOT EXISTS water_local(singleton INTEGER PRIMARY KEY CHECK(singleton=1),payload TEXT NOT NULL)",
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
    if(nextLock) profileLock=std::move(nextLock);
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
QJsonObject Store::liveLedger(const QString &id, const QString &expectedVersion) const
{
    const auto ledger = find("ledger", id);
    ensure(!ledger.isEmpty() && !ledger["deleted"].toBool(), QStringLiteral("此账本已不存在，请返回并重新选择"));
    ensure(expectedVersion.isEmpty() || ledger["version"].toString() == expectedVersion,
           QStringLiteral("此账本已更新，请重新打开明细后操作"));
    return ledger;
}
QString Store::addLedger(const QString &name)
{
    QString id = identity();
    transaction([&] { idle(); ensure(!name.trimmed().isEmpty() && name.size() <= 200, QStringLiteral("请输入不超过 200 字的账本名"));
        save("ledger", {{"id", id}, {"name", name.trimmed()}, {"deleted", false}, {"revision", 1}}); });
    return id;
}
void Store::renameLedger(const QString &id, const QString &name, const QString &expectedVersion)
{
    // 事务内核对打开表单时的版本，同步改名后旧表单不能覆盖新名称。
    transaction([&] { idle(); auto ledger = liveLedger(id, expectedVersion);
        ensure(!name.trimmed().isEmpty() && name.size() <= 200, QStringLiteral("请输入不超过 200 字的账本名"));
        ledger["name"] = name.trimmed(); save("ledger", ledger); });
}
void Store::deleteLedger(const QString &id, const QString &expectedVersion)
{
    // 核对确认对象后才生成账本及记录的删除操作，拒绝已删除或已更新的账本。
    transaction([&] { idle(); auto ledger = liveLedger(id, expectedVersion);
        for (const auto &value : entities("record")) { auto record = value.toObject(); if (record["ledgerId"].toString() == id) { record["deleted"] = true; save("record", record); } }
        ledger["deleted"] = true; save("ledger", ledger); });
}
/** 同名标签使用跨平台一致的 UTF-8 SHA-256 编号；已删除标签可明确重新添加。 */
QString Store::addTag(const QString &name)
{
    const auto text = name.trimmed();
    transaction([&] { idle(); ensure(!text.isEmpty() && text.size() <= 200, QStringLiteral("请输入不超过 200 字的标签"));
        const auto id = QString::fromLatin1(QCryptographicHash::hash(text.toUtf8(), QCryptographicHash::Sha256).toHex());
        auto tag = find("tag", id);
        if (!tag.isEmpty() && !tag["deleted"].toBool()) return;
        save("tag", {{"id", id}, {"name", text}, {"deleted", false}, {"revision", 1}});
    }); return text;
}
/** 只删除标签候选项及所有同名导入版本，不修改记录中已经保存的标签文字。 */
void Store::deleteTag(const QString &name)
{
    transaction([&] { idle(); bool found = false;
        for (const auto &value : entities("tag")) { auto tag = value.toObject(); if (tag["name"].toString() == name) { tag["deleted"] = true; save("tag", tag); found = true; } }
        ensure(found, QStringLiteral("标签已被删除，请重新选择"));
    });
}
void Store::start(const QString &ledgerId, const QString &task, const QString &tag)
{
    transaction([&] { idle(); const auto ledger = find("ledger", ledgerId);
        ensure(!ledger.isEmpty() && !ledger["deleted"].toBool(), QStringLiteral("请先选择账本"));
        ensure(!task.trimmed().isEmpty() && task.size() <= 10000, QStringLiteral("请输入不超过一万字的事项"));
        bool validTag = tag.isEmpty(); for (const auto &value : entities("tag")) if (value.toObject()["name"].toString() == tag) validTag = true;
        ensure(validTag, QStringLiteral("标签已被删除，请重新选择"));
        QJsonObject record{{"id", identity()}, {"ledgerId", ledgerId}, {"task", task.trimmed()},
             {"started", QDateTime::currentMSecsSinceEpoch()}, {"ended", QJsonValue::Null}, {"deleted", false}, {"revision", 1}};
        if (!tag.isEmpty()) record["tag"] = tag;
        save("record", record); });
}
void Store::finish(qint64 ended, const QString &expectedVersion)
{
    transaction([&] { auto record = active(); ensure(!record.isEmpty(), QStringLiteral("没有进行中的记录"));
        ensure(expectedVersion.isEmpty() || record["version"].toString() == expectedVersion, QStringLiteral("任务已由其他设备更新，请核对后重试"));
        if (record.contains("segments")) { auto parts = taskSegments(record); auto last = parts.last().toObject(); last["ended"] = ended; parts[parts.size()-1] = last; record["segments"] = parts; }
        record["ended"] = ended; validateRecord(record); save("record", record); });
}
void Store::editRecord(const QString &id, const QString &ledgerId, const QString &task, qint64 started, qint64 ended, const QString &expectedVersion, const QString &tag)
{
    transaction([&] { idle(); liveLedger(ledgerId);
        auto record = id.isEmpty() ? QJsonObject{{"id", identity()}, {"revision", 1}, {"deleted", false}} : find("record", id);
        ensure(!record.isEmpty() && !record["deleted"].toBool() && (id.isEmpty() || record["ledgerId"].toString() == ledgerId), QStringLiteral("记录不存在或不属于此账本"));
        ensure(expectedVersion.isEmpty() || record["version"].toString() == expectedVersion, QStringLiteral("记录已被另一设备更新，请保留输入并重新打开修改"));
        ensure(!record.contains("segments") || (started == record["started"].toInteger() && ended == record["ended"].toInteger()), QStringLiteral("请在分段明细中修改起止时间"));
        record["ledgerId"] = ledgerId; record["task"] = task.trimmed(); record["started"] = started; record["ended"] = ended;
        // 未传入标签参数的旧调用保留原快照；显式清空才移除标签字段。
        if (!tag.isNull()) {
            bool valid = tag.isEmpty() || record["tag"].toString() == tag;
            for (const auto &value : entities("tag")) if (value.toObject()["name"].toString() == tag) valid = true;
            ensure(valid, QStringLiteral("标签已被删除，请重新选择"));
            if (tag.isEmpty()) record.remove("tag"); else record["tag"] = tag;
        }
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
    return {{"format", "lyushi.backup"}, {"formatVersion", 3}, {"tags", entities("tag", true)}, {"ledgers", entities("ledger", true)},
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
    ensure(data["format"].toString() == "lyushi.backup" && (data["formatVersion"].toInt() == 1 || ((data["formatVersion"].toInt() == 2 || data["formatVersion"].toInt() == 3) && data["tags"].isArray()))
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
        QSet<QString> tagIds;
        for (const auto &value : data["tags"].toArray()) { const auto entity = value.toObject(); validateLedger(entity);
            ensure(!tagIds.contains(entity["id"].toString()), QStringLiteral("备份有重复标签编号")); tagIds.insert(entity["id"].toString()); }
        auto preserve = [&](const QString &kind, const QJsonObject &entity, bool archived = false) {
            ensure(kind == "record" || kind == "ledger" || kind == "tag", QStringLiteral("冲突类型无效"));
            if (kind == "record") validateRecord(entity, archived); else validateLedger(entity);
            QJsonObject wrapped{{"kind", kind}, {"entity", entity}, {"archived", true}};
            const auto payload = QJsonDocument(wrapped).toJson(QJsonDocument::Compact);
            const auto hash = QCryptographicHash::hash(payload, QCryptographicHash::Sha256).toHex();
            QSqlQuery query(db); query.prepare("INSERT OR IGNORE INTO conflicts(id,kind,entity_id,payload) VALUES(?,?,?,?)");
            query.addBindValue(hash); query.addBindValue(kind); query.addBindValue(entity["id"].toString()); query.addBindValue(payload); execute(query);
            conflictCount += query.numRowsAffected();
            if (query.numRowsAffected() == 1) queueArchive(wrapped);
        };
        for (const auto &kind : {QString("tag"), QString("ledger"), QString("record")}) {
            for (const auto &value : data[kind == "ledger" ? "ledgers" : kind == "tag" ? "tags" : "records"].toArray()) {
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
    idle();
    // 单账本导出在文件选择返回后再次校验原账本，已被同步删除时不生成文件。
    if (!ledgerId.isEmpty()) liveLedger(ledgerId);
    auto quote = [](QString text) { if (!text.isEmpty() && QString("=+-@\t\r").contains(text.front())) text.prepend('\''); text.replace("\"", "\"\""); return "\"" + text + "\""; };
    auto date = [](qint64 ms) { return QDateTime::fromMSecsSinceEpoch(ms, QTimeZone::UTC).toOffsetFromUtc(28800).toString("yyyy-MM-dd HH:mm:ss.zzz"); };
    QString result = QString::fromUtf8("\xEF\xBB\xBF") + QStringLiteral("账本,事项,开始（北京时间）,结束（北京时间）,时长（秒）,记录编号,标签,区间序号,区间类型\r\n");
    for (const auto &value : entities("record")) {
        const auto record = value.toObject(); if (!ledgerId.isEmpty() && record["ledgerId"].toString() != ledgerId) continue;
        const auto ledger = find("ledger", record["ledgerId"].toString()); if (ledger["deleted"].toBool()) continue;
        // 专注和分心各占一行，同一任务共用编号；不追加任务汇总行参与求和。
        const auto parts = taskSegments(record);
        for (int i=0; i<parts.size(); ++i) { const auto row = segmentRecord(record,i);
            result += QStringList{quote(ledger["name"].toString()), quote(row["task"].toString()), quote(date(row["started"].toInteger())),
                quote(date(row["ended"].toInteger())), quote(QString::number((row["ended"].toInteger() - row["started"].toInteger()) / 1000.0, 'f', 3)), quote(record["id"].toString()), quote(row["tag"].toString()), quote(QString::number(i+1)), quote(parts[i].toObject()["kind"] == "focus" ? QStringLiteral("专注") : QStringLiteral("分心"))}.join(',') + "\r\n";
        }
    }
    return result;
}

/** 切换失败重新打开原库，不把不完整目标误显示为原账户。 */
void Store::useDirectory(const QString &directory)
{
    idle(); const auto previous=this->directory(); if(QFileInfo(directory).absoluteFilePath()==previous) return;
    try { openDirectory(directory); } catch (...) { openDirectory(previous); throw; }
}
QString Store::directory() const { return QFileInfo(db.databaseName()).absolutePath(); }
void Store::bindAccount(const QString &userId, const QString &serverId)
{
    ensure(userId.size()==64 && serverId.size()==64, QStringLiteral("账户身份无效"));
    transaction([&] { QSqlQuery query(db); query.prepare("SELECT key,value FROM sync_meta WHERE key IN ('accountId','boundServerId','serverId')"); execute(query); QMap<QString,QString> meta;
        while (query.next()) meta[query.value(0).toString()]=query.value(1).toString(); query.finish();
        ensure(meta.value("accountId").isEmpty() || meta["accountId"]==userId,QStringLiteral("设备密钥属于其他账户，请在账户设置中连接并切换"));
        ensure((meta.value("serverId").isEmpty() || meta["serverId"]==serverId) && (meta.value("boundServerId").isEmpty() || meta["boundServerId"]==serverId),QStringLiteral("服务器身份变化，请核对账户或重新连接独立数据空间"));
        for (const auto &entry : {qMakePair(QString("accountId"),userId),qMakePair(QString("boundServerId"),serverId)}) { query.prepare("INSERT OR REPLACE INTO sync_meta(key,value) VALUES(?,?)");query.addBindValue(entry.first);query.addBindValue(entry.second);execute(query); }
    });
}
