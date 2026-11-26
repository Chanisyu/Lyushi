#pragma once
#include <QJsonArray>
#include <QJsonObject>
#include <QSqlDatabase>
#include <functional>
#include <memory>
#include <QLockFile>

/**
 * 本地数据边界：账本、记录和待上传操作存入独立的正式应用数据库。
 * 所有修改经过事务；界面不得直接修改数据库。当前不发送任何网络请求。
 * JSON 使用 UTC 毫秒时间点及稳定编号，供安卓与桌面交换备份。
 */
class Store {
    QSqlDatabase db;
    std::unique_ptr<QLockFile> profileLock;
    void openDirectory(const QString &directory);
    void transaction(const std::function<void()> &action);
    void save(const QString &kind, QJsonObject entity);
    QJsonObject find(const QString &kind, const QString &id) const;
    void idle() const;
    void preserveRemote(const QString &kind, const QJsonObject &entity);
    void queueArchive(const QJsonObject &content);
public:
    explicit Store(const QString &directory);
    ~Store();
    /** 仅在当前账户空闲时切换数据库；目标库的活动任务保留原状。 */
    void useDirectory(const QString &directory);
    QString directory() const;
    /** 上传前绑定经过设备认证的用户及服务器身份，拒绝跨账户复用本地队列。 */
    void bindAccount(const QString &userId, const QString &serverId);
    QJsonArray entities(const QString &kind, bool includeDeleted = false) const;
    QJsonObject active() const;
    QString addLedger(const QString &name);
    void renameLedger(const QString &id, const QString &name);
    void deleteLedger(const QString &id);
    /** 标签采用名称的确定性身份跨端去重；删除保留任务历史快照。 */
    QString addTag(const QString &name);
    void deleteTag(const QString &name);
    void start(const QString &ledgerId, const QString &task, const QString &tag = {});
    void finish(qint64 ended, const QString &expectedVersion = {});
    /** 仅修改当前阶段的事项与标签；编号和版本共同阻止过期表单覆盖或重启计时。 */
    void editActiveMetadata(const QString &id, const QString &expectedVersion, const QString &task, const QString &tag);
    /** 原子切换分心区间，严格校验发起操作时的任务版本。 */
    void distract(const QString &task, const QString &tag, qint64 now, const QString &expectedVersion);
    void resumeFocus(qint64 now, const QString &expectedVersion);
    void editSegment(const QString &id, int index, const QString &task, const QString &tag, qint64 started, qint64 ended, const QString &expectedVersion);
    void editRecord(const QString &id, const QString &ledgerId, const QString &task, qint64 started, qint64 ended, const QString &expectedVersion = {}, const QString &tag = {});
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

/** 主任务分段读取与有效时长计算，不修改数据库。 */
QJsonArray taskSegments(const QJsonObject &record);
bool isDistracted(const QJsonObject &record);
qint64 focusDuration(const QJsonObject &record, qint64 now);
QJsonObject segmentRecord(const QJsonObject &record, int index);
/** 按服务端 JSON 转义规则计量，防止大量特殊字符导致本机接受而服务端拒绝。 */
qint64 taskPayloadSize(const QJsonObject &record);
