#pragma once
#include <QJsonArray>
#include <QJsonObject>
#include <QSqlDatabase>
#include <functional>

/**
 * 本地数据边界：账本、记录和待上传操作存入独立的正式应用数据库。
 * 所有修改经过事务；界面不得直接修改数据库。当前不发送任何网络请求。
 * JSON 使用 UTC 毫秒时间点及稳定编号，供安卓与桌面交换备份。
 */
class Store {
    QSqlDatabase db;
    void transaction(const std::function<void()> &action);
    void save(const QString &kind, QJsonObject entity);
    QJsonObject find(const QString &kind, const QString &id) const;
    void idle() const;
    void preserveRemote(const QString &kind, const QJsonObject &entity);
    void queueArchive(const QJsonObject &content);
public:
    explicit Store(const QString &directory);
    ~Store();
    QJsonArray entities(const QString &kind, bool includeDeleted = false) const;
    QJsonObject active() const;
    QString addLedger(const QString &name);
    void renameLedger(const QString &id, const QString &name);
    void deleteLedger(const QString &id);
    void start(const QString &ledgerId, const QString &task);
    void finish(qint64 ended);
    void editRecord(const QString &id, const QString &ledgerId, const QString &task, qint64 started, qint64 ended, const QString &expectedVersion = {});
    void deleteRecord(const QString &id);
    QJsonObject backup() const;
    QJsonObject importBackup(const QJsonObject &data);
    QString csv(const QString &ledgerId = {}) const;
    QJsonArray conflicts() const;
    bool hasConflicts() const;
    QJsonObject syncRequest() const;
    bool acceptSync(const QJsonObject &response, const QJsonArray &sent);
    int pendingCount() const;
};

/** 对失败统一抛出 UTF-8 错误，调用方必须明确告知用户。 */
void ensure(bool condition, const QString &message);
/** 校验不含活动状态的普通记录，拒绝非整数、反向或超范围时间。 */
void validateRecord(const QJsonObject &record, bool allowRunning = false);
void validateLedger(const QJsonObject &ledger);
