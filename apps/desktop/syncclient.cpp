#include "syncclient.h"
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QUrl>

SyncClient::SyncClient(Store &storage, QObject *parent) : QObject(parent), store(storage), network(this)
{
    timer.setInterval(5000); connect(&timer, &QTimer::timeout, this, [this] { request(); });
}
/** 连接配置不在请求中改变，密钥只发给配置的原始 HTTPS 地址，不跟随重定向。 */
void SyncClient::configure(const QString &url, const QString &deviceKey, bool allowLoopback)
{
    ensure(!inFlight, QStringLiteral("正在同步，请稍后更改连接"));
    timer.stop(); base = url.trimmed(); key = deviceKey.trimmed();
    while (base.endsWith('/')) base.chop(1);
    if (base.isEmpty() || key.isEmpty()) { status = QStringLiteral("尚未配置服务器和设备密钥"); return; }
    const QUrl address(base);
    ensure(address.isValid() && !address.host().isEmpty() && address.userInfo().isEmpty() && !address.hasQuery() && !address.hasFragment()
        && (address.scheme() == "https" || (allowLoopback && address.scheme() == "http" && address.host() == "127.0.0.1")), QStringLiteral("同步需要有效的 HTTPS 地址"));
    ensure(QRegularExpression("^[0-9a-f]{64}$").match(key).hasMatch(), QStringLiteral("设备密钥应为 64 位小写十六进制文字"));
    backoff = 5000; timer.setInterval(backoff); timer.start(); QTimer::singleShot(0, this, [this] { request(); });
}
/** 一次上传至多五十个操作；响应及游标应用成功后才发送下一页或下一批。 */
void SyncClient::request()
{
    if (inFlight || base.isEmpty() || key.isEmpty()) return;
    try {
        const auto body = store.syncRequest(); const auto sent = body["operations"].toArray();
        QNetworkRequest request(QUrl(base + "/v1/sync")); request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
        request.setRawHeader("Authorization", "Bearer " + key.toUtf8()); request.setTransferTimeout(15000);
        request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
        inFlight = true; auto *reply = network.post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
        connect(reply, &QNetworkReply::downloadProgress, reply, [reply](qint64 received, qint64) { if (received > 8 * 1024 * 1024) reply->abort(); });
        connect(reply, &QNetworkReply::finished, this, [this, reply, sent] {
            inFlight = false;
            try {
                const int code = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
                ensure(reply->error() == QNetworkReply::NoError && code == 200, code == 401 ? QStringLiteral("设备密钥无效或已撤销") : code == 409 ? QStringLiteral("同步未提交，请核对服务器身份和数据版本") : QStringLiteral("本机已保存，等待重连"));
                QJsonParseError error; const auto bytes = reply->readAll(); ensure(bytes.size() <= 8 * 1024 * 1024, QStringLiteral("同步响应过大"));
                const auto document = QJsonDocument::fromJson(bytes, &error); ensure(error.error == QJsonParseError::NoError && document.isObject(), QStringLiteral("服务器响应无效"));
                const auto response = document.object(); const bool changed = store.acceptSync(response, sent), reconnected = failed;
                failed = false; backoff = 5000; timer.setInterval(backoff);
                const bool more = response["more"].toBool() || store.pendingCount() > 0;
                status = more ? QStringLiteral("正在同步，本机数据已保存") : QStringLiteral("已同步");
                if (updated) updated(changed, reconnected);
                if (more) QTimer::singleShot(0, this, [this] { this->request(); }); else if (settled) settled(true);
            } catch (const std::exception &error) {
                failed = true; status = QString::fromUtf8(error.what()); backoff = qMin(60000, backoff * 2); timer.setInterval(backoff);
                if (updated) updated(false, false); if (settled) settled(false);
            }
            reply->deleteLater();
        });
    } catch (const std::exception &error) { status = QString::fromUtf8(error.what()); if (updated) updated(false, false); if (settled) settled(false); }
}
