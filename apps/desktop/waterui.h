#pragma once
#include <QDialog>
#include <functional>

class Store;
class SyncClient;

/** 饮水窗口不占用时间账本的管理锁。返回后，原主窗口继续显示同一活动任务。 */
void showWater(QWidget *owner, Store &store, SyncClient &sync);
void waterSelfTest(Store &store, const QString &snapshot = {});
