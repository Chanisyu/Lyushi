#pragma once
#include "store.h"
#include <QNetworkAccessManager>
#include <QObject>
#include <QTimer>

/** 异步 HTTPS 协调器：单次请求、退避重试、稳定操作编号，失败保持本地队列。 */
class SyncClient : public QObject {
    Store &store;
    QNetworkAccessManager network;
    QTimer timer;
    QString base, key;
    QString verifiedServer;
    bool waterInFlight = false;
    qint64 nextWaterAttempt = 0;
    bool inFlight = false, failed = false, identified = false;
    int backoff = 5000;
public:
    QString status = QStringLiteral("尚未配置同步");
    std::function<void(bool, bool)> updated;
    std::function<void(bool)> settled;
    QString waterStatus = QStringLiteral("本机记录");
    std::function<void()> waterUpdated;
    std::function<void(bool)> waterSettled;
    explicit SyncClient(Store &storage, QObject *parent = nullptr);
    void configure(const QString &url, const QString &deviceKey, bool allowLoopback = false);
    void request();
    void requestWater(bool force = false);
    bool canConfigure() const { return !inFlight && !waterInFlight; }
};
