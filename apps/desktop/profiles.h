#pragma once
#include "store.h"
#include "syncclient.h"
#include <QWidget>
/** 账户编号映射到独立数据目录；本地旧版库不自动加入新账户。 */
QString accountProfile();
QString accountDirectory(const QString &profile);
QString accountServer(const QString &profile);
bool showAccountSettings(QWidget *owner, Store &store, SyncClient &sync);

/** 连接验证只读取账户身份，不发送本地业务数据。 */
QJsonObject identifyAccount(const QString &address, const QString &key);
