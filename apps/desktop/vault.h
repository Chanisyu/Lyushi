#pragma once
#include "store.h"
#include <QDir>
#include <QFile>
#include <QProcess>
#include <QSaveFile>
#include <QStandardPaths>
#ifdef Q_OS_WIN
#include <windows.h>
#include <wincrypt.h>
#endif

/**
 * 设备密钥按系统能力保存：Windows 使用当前用户 DPAPI，Linux 使用 Secret Service。
 * 失败明确报错，不回退明文；不把密钥放到命令行、日志或 JSON 备份里。
 */
class Vault {
public:
    static QString read()
    {
#ifdef Q_OS_WIN
        QFile file(QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation) + "/device-key.bin");
        if (!file.exists()) return {};
        ensure(file.open(QIODevice::ReadOnly) && file.size() <= 65536, QStringLiteral("无法读取密钥密文"));
        auto encrypted = file.readAll(); DATA_BLOB input{DWORD(encrypted.size()), reinterpret_cast<BYTE *>(encrypted.data())}, output{};
        ensure(CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output), QStringLiteral("系统无法解密设备密钥，请重新填写"));
        const auto result = QString::fromUtf8(reinterpret_cast<char *>(output.pbData), output.cbData);
        SecureZeroMemory(output.pbData, output.cbData); LocalFree(output.pbData); return result;
#else
        QProcess process; process.start("secret-tool", {"lookup", "service", "dev.lyushi.app"});
        ensure(process.waitForStarted(3000), QStringLiteral("需要安装 libsecret-tools 并启用系统密钥环"));
        if (!process.waitForFinished(10000)) { process.kill(); process.waitForFinished(); ensure(false, QStringLiteral("系统密钥环未响应")); }
        const auto errors = process.readAllStandardError();
        ensure(errors.isEmpty() && process.exitStatus() == QProcess::NormalExit && (process.exitCode() == 0 || process.exitCode() == 1), QStringLiteral("系统密钥环不可用，请先解锁"));
        return QString::fromUtf8(process.readAllStandardOutput()).trimmed();
#endif
    }
    static void write(const QString &key)
    {
#ifdef Q_OS_WIN
        const auto directory = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
        ensure(QDir().mkpath(directory), QStringLiteral("无法创建密钥目录")); const auto path = directory + "/device-key.bin";
        if (key.isEmpty()) { if (QFile::exists(path)) ensure(QFile::remove(path), QStringLiteral("清除密钥失败")); return; }
        auto bytes = key.toUtf8(); DATA_BLOB input{DWORD(bytes.size()), reinterpret_cast<BYTE *>(bytes.data())}, output{};
        ensure(CryptProtectData(&input, L"Lyushi device key", nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output), QStringLiteral("系统无法加密设备密钥"));
        const QByteArray encrypted(reinterpret_cast<char *>(output.pbData), output.cbData); LocalFree(output.pbData); bytes.fill(0);
        QSaveFile file(path); ensure(file.open(QIODevice::WriteOnly), QStringLiteral("无法保存密钥密文"));
        ensure(file.write(encrypted) == encrypted.size() && file.commit(), QStringLiteral("保存密钥密文失败"));
#else
        QProcess process;
        process.start("secret-tool", key.isEmpty() ? QStringList{"clear", "service", "dev.lyushi.app"} : QStringList{"store", "--label=柳时设备密钥", "service", "dev.lyushi.app"});
        ensure(process.waitForStarted(3000), QStringLiteral("需要安装 libsecret-tools 并启用系统密钥环"));
        if (!key.isEmpty()) process.write(key.toUtf8()); process.closeWriteChannel();
        if (!process.waitForFinished(15000)) { process.kill(); process.waitForFinished(); ensure(false, QStringLiteral("系统密钥环未响应")); }
        ensure(process.exitStatus() == QProcess::NormalExit && process.readAllStandardError().isEmpty()
            && (process.exitCode() == 0 || (key.isEmpty() && process.exitCode() == 1)), QStringLiteral("系统密钥环保存失败，请先解锁"));
#endif
        ensure(read() == key, QStringLiteral("密钥保存后校验失败"));
    }
};
