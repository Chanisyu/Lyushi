/**
 * 桌面入口：日常记录只占一个小窗口；账本管理、历史和文件操作集中在非记录页面。
 * 计时刷新只读数据，不每秒写盘；关闭窗口不会隐式结束活动。
 */
#include "store.h"
#include "vault.h"
#include "syncclient.h"
#include "ui-language.h"
#include <QtWidgets>
#include <QJsonDocument>
#include <QSaveFile>
#include <QTimeZone>
#include <QSslSocket>
#include <QUuid>
#include <QPointer>
#include <algorithm>

/** 固定北京时间；时长独立按总小时数显示，超过一天也不会回绕。 */
static QTimeZone beijing() { return QTimeZone::fromSecondsAheadOfUtc(28800); }
static QString dateText(qint64 ms) { return QDateTime::fromMSecsSinceEpoch(ms, beijing()).toString("yyyy-MM-dd HH:mm:ss"); }
static QString durationText(qint64 ms)
{
    qint64 s = qMax<qint64>(0, ms / 1000);
    return QString("%1:%2:%3").arg(s / 3600, 2, 10, QChar('0')).arg(s / 60 % 60, 2, 10, QChar('0')).arg(s % 60, 2, 10, QChar('0'));
}
/** 写完临时文件后再替换目标，失败保留原文件，不把半份备份当作成功。 */
static void writeFile(const QString &path, const QByteArray &bytes)
{
    QSaveFile file(path); ensure(file.open(QIODevice::WriteOnly), file.errorString());
    ensure(file.write(bytes) == bytes.size() && file.commit(), file.errorString());
}
/** 统一提示保存或校验错误；回调失败时页面维持原有数据库状态。 */
static void safely(QWidget *owner, const std::function<void()> &action)
{
    try { action(); } catch (const std::exception &error) { QMessageBox::warning(owner, QStringLiteral("操作未完成"), QString::fromUtf8(error.what())); }
}

/** 远端开始计时时，连同嵌套的编辑和账本窗口一起关闭，防止只关掉最上层表单。 */
static void closeManagementDialogs(QWidget *owner)
{
    // 独立窗口不保证被 isAncestorOf 或 activeModalWidget 识别，使用 QObject 归属树。
    QList<QPointer<QDialog>> dialogs;
    for (auto *dialog : owner->findChildren<QDialog *>()) dialogs.append(dialog);
    for (auto it = dialogs.crbegin(); it != dialogs.crend(); ++it)
        if (*it && (*it)->isVisible()) (*it)->reject();
}
/** 使用系统色彩提示切换三种模式，避免把本机外观同步给其他设备。 */
static void applyTheme()
{
    const int theme = QSettings().value("theme", 0).toInt();
    qApp->styleHints()->setColorScheme(theme == 1 ? Qt::ColorScheme::Light : theme == 2 ? Qt::ColorScheme::Dark : Qt::ColorScheme::Unknown);
}

class Window : public QWidget {
    Store &store;
    SyncClient sync;
    QComboBox *ledgers, *tags;
    QPushButton *addTagButton, *deleteTagButton;
    QLineEdit *task;
    QLabel *clock, *elapsed, *status;
    QPushButton *action, *details, *add;
    QString selected;
    QString actionRecordAtPress;

    /** 下拉项保存原始名称，历史已删除标签仅在编辑原记录时作为快照显示。 */
    void fillTags(QComboBox *choice, const QString &selectedTag)
    {
        QSignalBlocker blocker(choice); choice->clear(); choice->addItem(QStringLiteral("无标签"), QStringLiteral(""));
        QStringList names;
        for (const auto &value : store.entities("tag")) names.append(value.toObject()["name"].toString());
        names.removeDuplicates(); names.sort();
        for (const auto &name : names) choice->addItem(name, name);
        if (!selectedTag.isEmpty() && !names.contains(selectedTag)) choice->addItem(selectedTag + QStringLiteral("（已删除）"), selectedTag);
        choice->setCurrentIndex(qMax(0, choice->findData(selectedTag)));
    }
    /** 刷新时保留固定账本编号；同名账本不会因名字相同而混在一起。 */
    void reload()
    {
        const auto current = store.active();
        if (!current.isEmpty()) selected = current["ledgerId"].toString();
        QSignalBlocker blocker(ledgers); ledgers->clear();
        for (const auto &value : store.entities("ledger")) {
            const auto ledger = value.toObject(); ledgers->addItem(ledger["name"].toString(), ledger["id"].toString());
        }
        int index = ledgers->findData(selected); if (index < 0) index = ledgers->count() ? 0 : -1;
        ledgers->setCurrentIndex(index); selected = ledgers->currentData().toString();
        QSettings().setValue("ledger", selected);
        const auto tag = current.isEmpty() ? tags->currentData().toString() : current["tag"].toString();
        // 普通新任务不保留被另一设备删除的候选；进行中记录继续展示其历史快照。
        bool live = tag.isEmpty(); for (const auto &value : store.entities("tag")) if (value.toObject()["name"].toString() == tag) live = true;
        fillTags(tags, !current.isEmpty() || live ? tag : QStringLiteral("")); refresh();
    }
    /** 回调从开始时间重新计算；操作系统暂停回调不会累积丢秒。 */
    void refresh()
    {
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        clock->setText(dateText(now) + QStringLiteral(" · 北京时间"));
        const auto current = store.active(); const bool running = !current.isEmpty();
        ledgers->setEnabled(!running); details->setEnabled(!running && !selected.isEmpty()); add->setEnabled(!running);
        tags->setEnabled(!running); addTagButton->setEnabled(!running);
        deleteTagButton->setEnabled(!running && !tags->currentData().toString().isEmpty());
        task->setEnabled(!running); action->setEnabled(running || !selected.isEmpty());
        action->setText(running ? QStringLiteral("结束记录") : QStringLiteral("开始记录"));
        if (running && task->text() != current["task"].toString()) task->setText(current["task"].toString());
        elapsed->setText(running ? durationText(now - current["started"].toInteger()) : "00:00:00");
        status->setText(running && now < current["started"].toInteger() ? QStringLiteral("设备时间早于开始时间，请核对系统时间") :
            sync.status + (store.hasConflicts() ? QStringLiteral(" · 已保留冲突版本") : ""));
    }
    /** 新建和修改共用精确到秒的北京时间表单；保存校验在存储层再次执行。 */
    void edit(QWidget *owner, const QJsonObject &record = {})
    {
        QDialog dialog(owner); dialog.setWindowTitle(record.isEmpty() ? QStringLiteral("补记") : QStringLiteral("修改记录"));
        QFormLayout layout(&dialog); QPlainTextEdit text(record["task"].toString()); text.setMinimumWidth(330);
        QComboBox tagChoice; fillTags(&tagChoice, record["tag"].toString());
        layout.addRow(QStringLiteral("标签"), &tagChoice);
        QDateTimeEdit begin, end;
        for (auto *input : {&begin, &end}) { input->setTimeZone(beijing()); input->setDisplayFormat("yyyy-MM-dd HH:mm:ss"); input->setCalendarPopup(true); }
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        begin.setDateTime(QDateTime::fromMSecsSinceEpoch(record.isEmpty() ? now - 60000 : record["started"].toInteger(), beijing()));
        end.setDateTime(QDateTime::fromMSecsSinceEpoch(record.isEmpty() ? now : record["ended"].toInteger(), beijing()));
        layout.addRow(QStringLiteral("事项"), &text); layout.addRow(QStringLiteral("开始（北京时间）"), &begin); layout.addRow(QStringLiteral("结束（北京时间）"), &end);
        QDialogButtonBox buttons(QDialogButtonBox::Save | QDialogButtonBox::Cancel); layout.addRow(&buttons);
        connect(&buttons, &QDialogButtonBox::rejected, &dialog, &QDialog::reject);
        connect(&buttons, &QDialogButtonBox::accepted, &dialog, [&] { safely(&dialog, [&] {
            store.editRecord(record["id"].toString(), selected, text.toPlainText(), begin.dateTime().toMSecsSinceEpoch(), end.dateTime().toMSecsSinceEpoch(), record["version"].toString(), tagChoice.currentData().toString()); dialog.accept(); }); });
        dialog.exec();
    }
    /** 冲突中的原始版本以只读方式展示，第一版不擅自决定用哪一方覆盖。 */
    void showConflicts(QWidget *owner)
    {
        ensure(store.active().isEmpty(), QStringLiteral("请先结束记录再查看冲突版本"));
        QDialog dialog(owner); dialog.setWindowTitle(QStringLiteral("保留的不同版本")); dialog.resize(620, 440);
        QVBoxLayout layout(&dialog); QLabel note(QStringLiteral("联网或导入时发现的不同内容已保留。这里是存档，查看不会重新开始计时；JSON 备份也包含这些内容。")); note.setWordWrap(true); layout.addWidget(&note);
        QMap<QString,QString> names; for (const auto &entry : store.entities("ledger", true)) { const auto book = entry.toObject(); names[book["id"].toString()] = book["name"].toString(); }
        QStringList lines; int count = 0;
        for (const auto &entry : store.conflicts()) {
            const auto content = entry.toObject(), entity = content["entity"].toObject();
            lines << QStringLiteral("版本 %1%2").arg(++count).arg(entity["deleted"].toBool() ? QStringLiteral(" · 删除状态，原内容仍保留") : QString{});
            if ((content["kind"].toString() == "ledger" || content["kind"].toString() == "tag")) lines << QStringLiteral("名称：%1").arg(entity["name"].toString());
            else lines << entity["task"].toString() << QStringLiteral("标签：%1").arg(entity["tag"].toString()) << QStringLiteral("账本：%1").arg(names.value(entity["ledgerId"].toString(), QStringLiteral("已删除或未知账本")))
                << QStringLiteral("开始：%1").arg(dateText(entity["started"].toInteger())) << (entity["ended"].isNull() ? QStringLiteral("结束：此版本尚未结束（存档）") : QStringLiteral("结束：%1").arg(dateText(entity["ended"].toInteger())));
            lines << "";
        }
        QPlainTextEdit text; text.setReadOnly(true); text.setPlainText(lines.isEmpty() ? QStringLiteral("没有保留的冲突。") : lines.join('\n')); layout.addWidget(&text);
        QDialogButtonBox buttons(QDialogButtonBox::Close); layout.addWidget(&buttons); connect(&buttons, &QDialogButtonBox::rejected, &dialog, &QDialog::reject); dialog.exec();
    }
    /** 文件入口只在非记录时开放；读取有体积上限，导入前确认范围与保留规则。 */
    void fileAction(QWidget *owner, int mode)
    {
        ensure(store.active().isEmpty(), QStringLiteral("请先结束记录"));
        if (mode == 0) {
            const auto path = QFileDialog::getOpenFileName(owner, QStringLiteral("导入 JSON 备份"), {}, "JSON (*.json)"); if (path.isEmpty()) return;
            QFile file(path); ensure(file.open(QIODevice::ReadOnly), file.errorString()); ensure(file.size() <= 32 * 1024 * 1024, QStringLiteral("文件超过 32 MiB，请分批处理"));
            QJsonParseError error; const auto document = QJsonDocument::fromJson(file.readAll(), &error);
            ensure(error.error == QJsonParseError::NoError && document.isObject(), QStringLiteral("不是有效 JSON 备份"));
            if (QMessageBox::question(owner, QStringLiteral("合并导入"), QStringLiteral("将合并文件中的账本和记录。相同内容去重，不同版本另存为冲突，不覆盖本机。继续？")) != QMessageBox::Yes) return;
            const auto result = store.importBackup(document.object());
            QMessageBox::information(owner, QStringLiteral("导入完成"), QStringLiteral("新增 %1，重复 %2，另存冲突 %3。")
                .arg(result["added"].toInt()).arg(result["duplicate"].toInt()).arg(result["conflicts"].toInt()));
        } else {
            const bool json = mode == 1;
            const auto path = QFileDialog::getSaveFileName(owner, QStringLiteral("导出"), json ? "柳时备份.json" : "时间记录.csv", json ? "JSON (*.json)" : "CSV (*.csv)"); if (path.isEmpty()) return;
            writeFile(path, json ? QJsonDocument(store.backup()).toJson() : store.csv(mode == 2 ? selected : QString{}).toUtf8());
            QMessageBox::information(owner, QStringLiteral("导出完成"), QStringLiteral("文件已保存。"));
        }
    }
    /** 账本页面的表格严格按账本编号筛选，删除与改名均再次向存储层校验。 */
    void showDetails()
    {
        if (!store.active().isEmpty()) return;
        QDialog dialog(this); dialog.setWindowTitle(ledgers->currentText()); dialog.resize(780, 540); QVBoxLayout layout(&dialog);
        QTableWidget table; table.setColumnCount(5); table.setHorizontalHeaderLabels({QStringLiteral("事项"), QStringLiteral("开始"), QStringLiteral("结束"), QStringLiteral("时长"), QStringLiteral("标签")});
        table.setSelectionBehavior(QAbstractItemView::SelectRows); table.setSelectionMode(QAbstractItemView::SingleSelection); table.setEditTriggers(QAbstractItemView::NoEditTriggers);
        table.horizontalHeader()->setSectionResizeMode(QHeaderView::ResizeToContents); table.horizontalHeader()->setSectionResizeMode(0, QHeaderView::Stretch); layout.addWidget(&table);
        QJsonArray records;
        auto fill = [&] {
            records = {}; QList<QJsonObject> sorted;
            for (const auto &value : store.entities("record")) if (value.toObject()["ledgerId"].toString() == selected) sorted.append(value.toObject());
            std::sort(sorted.begin(), sorted.end(), [](const auto &a, const auto &b) { return a["started"].toInteger() > b["started"].toInteger(); });
            table.setRowCount(sorted.size()); int row = 0;
            for (const auto &record : sorted) { records.append(record);
                const QStringList columns{record["task"].toString(), dateText(record["started"].toInteger()), dateText(record["ended"].toInteger()), durationText(record["ended"].toInteger() - record["started"].toInteger()), record["tag"].toString()};
                for (int c = 0; c < columns.size(); ++c) table.setItem(row, c, new QTableWidgetItem(columns[c])); ++row; }
        };
        auto chosen = [&] { ensure(table.currentRow() >= 0, QStringLiteral("请先选择一条记录")); return records.at(table.currentRow()).toObject(); };
        auto *row = new QHBoxLayout; layout.addLayout(row);
        auto button = [&](const QString &text, const std::function<void()> &callback) { auto *b = new QPushButton(text); row->addWidget(b); connect(b, &QPushButton::clicked, &dialog, [&, callback] { safely(&dialog, callback); }); };
        button(QStringLiteral("补记"), [&] { edit(&dialog); fill(); });
        button(QStringLiteral("修改"), [&] { edit(&dialog, chosen()); fill(); });
        button(QStringLiteral("删除记录"), [&] { const auto record = chosen(); if (QMessageBox::question(&dialog, QStringLiteral("删除记录"), record["task"].toString() + QStringLiteral("\n确认删除这条记录？")) == QMessageBox::Yes) { store.deleteRecord(record["id"].toString()); fill(); } });
        button(QStringLiteral("改名"), [&] { bool ok; const auto name = QInputDialog::getText(&dialog, QStringLiteral("账本改名"), QStringLiteral("账本名称"), QLineEdit::Normal, ledgers->currentText(), &ok); if (ok) { store.renameLedger(selected, name); reload(); dialog.setWindowTitle(ledgers->currentText()); } });
        button(QStringLiteral("删除账本"), [&] { bool ok; const auto name = ledgers->currentText(); const auto answer = QInputDialog::getText(&dialog, QStringLiteral("删除整个账本"), QStringLiteral("将删除 %1 条记录。输入账本名“%2”确认：").arg(records.size()).arg(name), QLineEdit::Normal, {}, &ok);
            if (ok) { ensure(answer == name, QStringLiteral("名称不一致，未删除")); store.deleteLedger(selected); dialog.accept(); } });
        auto *files = new QHBoxLayout; layout.addLayout(files); row = files;
        button(QStringLiteral("导入 JSON"), [&] { fileAction(&dialog, 0); reload(); fill(); });
        button(QStringLiteral("备份全部 JSON"), [&] { fileAction(&dialog, 1); });
        button(QStringLiteral("导出本账本 CSV"), [&] { fileAction(&dialog, 2); });
        button(QStringLiteral("导出全部 CSV"), [&] { fileAction(&dialog, 3); });
        button(QStringLiteral("查看冲突"), [&] { showConflicts(&dialog); });
        fill(); dialog.exec(); reload();
    }
    /** 外观属于本机设置；密钥单独进入系统安全存储，记录时锁定连接配置。 */
    void settings()
    {
        QDialog dialog(this); dialog.setWindowTitle(QStringLiteral("设置")); QFormLayout layout(&dialog);
        layout.addRow(QStringLiteral("版本"), new QLabel(QCoreApplication::applicationVersion() + QStringLiteral("（测试版）")));
        QComboBox language; language.addItem(QStringLiteral("简体中文")); layout.addRow(QStringLiteral("语言"), &language);
        QComboBox theme; theme.addItems({QStringLiteral("跟随系统"), QStringLiteral("白天"), QStringLiteral("黑夜")}); theme.setCurrentIndex(QSettings().value("theme", 0).toInt()); layout.addRow(QStringLiteral("主题"), &theme);
        // 新安装由用户填写服务器地址，已有设置保持有效。
        QLineEdit server(QSettings().value("server", "").toString()); server.setPlaceholderText("https://"); server.setEnabled(store.active().isEmpty()); layout.addRow(QStringLiteral("服务器地址"), &server);
        QLineEdit key; key.setEchoMode(QLineEdit::Password); key.setEnabled(store.active().isEmpty()); layout.addRow(QStringLiteral("设备密钥"), &key);
        bool loaded = false; safely(&dialog, [&] { key.setText(Vault::read()); loaded = true; });
        QLabel note(QStringLiteral("填写设备密钥后自动同步；离线仍可记录。密钥使用系统安全存储，冲突版本分别保留。")); note.setWordWrap(true); layout.addRow(&note);
        QDialogButtonBox buttons(QDialogButtonBox::Save | QDialogButtonBox::Cancel); layout.addRow(&buttons);
        connect(&buttons, &QDialogButtonBox::rejected, &dialog, &QDialog::reject);
        connect(&buttons, &QDialogButtonBox::accepted, &dialog, [&] { safely(&dialog, [&] {
            const auto address = server.text().trimmed(); const QUrl url(address);
            ensure(address.isEmpty() || (url.isValid() && url.scheme() == "https" && !url.host().isEmpty() && url.userInfo().isEmpty() && !url.hasQuery() && !url.hasFragment()), QStringLiteral("请输入不含用户名、密码、查询或片段的 HTTPS 地址"));
            if (store.active().isEmpty()) {
                ensure(sync.canConfigure(), QStringLiteral("正在同步，请稍后保存连接设置"));
                if (loaded || !key.text().isEmpty()) Vault::write(key.text().trimmed());
                sync.configure(address, key.text().trimmed());
            }
            QSettings prefs; prefs.setValue("theme", theme.currentIndex()); prefs.setValue("server", address); prefs.sync(); ensure(prefs.status() == QSettings::NoError, QStringLiteral("设置保存失败")); applyTheme(); dialog.accept();
        }); });
        dialog.exec();
    }
public:
    explicit Window(Store &storage) : store(storage), sync(storage, this)
    {
        selected = QSettings().value("ledger").toString(); setWindowTitle(QStringLiteral("柳时")); resize(460, 440);
        auto *layout = new QVBoxLayout(this); layout->setContentsMargins(28, 24, 28, 24); layout->setSpacing(16);
        auto *header = new QHBoxLayout; auto *title = new QLabel(QStringLiteral("柳时")); title->setStyleSheet("font-size:26px;font-weight:600"); header->addWidget(title); header->addStretch();
        auto *syncButton = new QPushButton(QStringLiteral("同步")); header->addWidget(syncButton);
        auto *settingsButton = new QPushButton(QStringLiteral("设置")); header->addWidget(settingsButton); layout->addLayout(header);
        clock = new QLabel; layout->addWidget(clock); auto *ledgerRow = new QHBoxLayout;
        ledgers = new QComboBox; ledgers->setPlaceholderText(QStringLiteral("请先新建时间账本")); ledgers->setMinimumContentsLength(12); ledgerRow->addWidget(ledgers, 1);
        add = new QPushButton(QStringLiteral("新建账本")); ledgerRow->addWidget(add); layout->addLayout(ledgerRow);
        task = new QLineEdit; task->setPlaceholderText(QStringLiteral("现在正在做什么？")); task->setMaxLength(10000); task->setMinimumHeight(40); layout->addWidget(task);
        // 标签选择紧邻事项，右侧删除针对当前标签，新增后立即进入候选列表。
        auto *tagRow = new QHBoxLayout; tagRow->addWidget(new QLabel(QStringLiteral("标签")));
        tags = new QComboBox; tags->setObjectName("taskTag"); tagRow->addWidget(tags, 1);
        deleteTagButton = new QPushButton(QStringLiteral("删除标签")); tagRow->addWidget(deleteTagButton);
        addTagButton = new QPushButton(QStringLiteral("新增标签")); tagRow->addWidget(addTagButton); layout->addLayout(tagRow);
        connect(tags, &QComboBox::currentIndexChanged, this, [this] { refresh(); });
        connect(addTagButton, &QPushButton::clicked, this, [this] { safely(this, [this] {
            bool ok; const auto name = QInputDialog::getText(this, QStringLiteral("新增标签"), QStringLiteral("标签名称（最多 200 字）"), QLineEdit::Normal, {}, &ok);
            if (ok) { const auto added = store.addTag(name); reload(); tags->setCurrentIndex(tags->findData(added)); sync.request(); }
        }); });
        connect(deleteTagButton, &QPushButton::clicked, this, [this] { safely(this, [this] {
            const auto name = tags->currentData().toString(); if (name.isEmpty()) return;
            if (QMessageBox::question(this, QStringLiteral("删除标签"), QStringLiteral("删除标签“%1”？历史任务保留原标签。删除会同步到其他设备。").arg(name)) == QMessageBox::Yes) {
                store.deleteTag(name); tags->setCurrentIndex(0); reload(); sync.request();
            }
        }); });
        elapsed = new QLabel; elapsed->setStyleSheet("font-size:40px"); elapsed->setAlignment(Qt::AlignCenter); layout->addWidget(elapsed);
        action = new QPushButton; action->setMinimumHeight(48); layout->addWidget(action);
        details = new QPushButton(QStringLiteral("账本明细与管理")); layout->addWidget(details);
        auto *importButton = new QPushButton(QStringLiteral("从 JSON 备份恢复")); layout->addWidget(importButton);
        status = new QLabel; status->setWordWrap(true); layout->addWidget(status);
        connect(add, &QPushButton::clicked, this, [&] { safely(this, [&] { bool ok; const auto name = QInputDialog::getText(this, QStringLiteral("新建账本"), QStringLiteral("账本名称"), QLineEdit::Normal, {}, &ok); if (ok) { selected = store.addLedger(name); reload(); } }); });
        connect(ledgers, &QComboBox::currentIndexChanged, this, [&] { selected = ledgers->currentData().toString(); QSettings().setValue("ledger", selected); });
        // 按下和释放按钮之间若远端改变了状态，拒绝把“结束”误解释成“开始”。
        connect(action, &QPushButton::pressed, this, [this] { actionRecordAtPress = store.active()["id"].toString(); });
        connect(action, &QPushButton::clicked, this, [&] { safely(this, [&] {
            ensure(actionRecordAtPress == store.active()["id"].toString(), QStringLiteral("计时状态已由其他设备改变，请核对页面后再操作"));
            if (store.active().isEmpty()) store.start(selected, task->text(), tags->currentData().toString()); else { store.finish(QDateTime::currentMSecsSinceEpoch()); task->clear(); } reload(); sync.request();
        }); });
        connect(details, &QPushButton::clicked, this, [&] { safely(this, [&] { showDetails(); }); });
        connect(settingsButton, &QPushButton::clicked, this, [&] { settings(); });
        connect(syncButton, &QPushButton::clicked, this, [&] { sync.request(); });
        auto *conflictButton = new QPushButton(QStringLiteral("查看保留的冲突版本")); layout->addWidget(conflictButton);
        connect(conflictButton, &QPushButton::clicked, this, [&] { safely(this, [&] { showConflicts(this); }); });
        connect(importButton, &QPushButton::clicked, this, [&] { safely(this, [&] { fileAction(this, 0); reload(); }); });
        auto *timer = new QTimer(this); connect(timer, &QTimer::timeout, this, [this, importButton, conflictButton] { safely(this, [&] { refresh(); importButton->setEnabled(store.active().isEmpty()); conflictButton->setEnabled(store.active().isEmpty()); }); }); timer->start(1000);
        reload(); importButton->setEnabled(store.active().isEmpty());
        // 收到另一设备的进行中状态后关闭管理窗口，立即执行记录期间的查看限制。
        sync.updated = [this](bool changed, bool reconnected) {
            if (changed) { if (!store.active().isEmpty()) closeManagementDialogs(this); reload(); }
            refresh(); if (reconnected) QMessageBox::information(this, QStringLiteral("已重新连接"), QStringLiteral("已重新连接服务器，双方内容均已保留。发现的冲突可在主页面查看。"));
        };
        QTimer::singleShot(0, this, [this] { try { sync.configure(QSettings().value("server", "").toString(), Vault::read()); }
            catch (const std::exception &error) { sync.status = QString::fromUtf8(error.what()); refresh(); } });
    }
};

/** 自检使用调用者指定的隔离目录，覆盖跨进程恢复、账本隔离、删除传播数据与导入回滚。 */
static void selfTest(Store &store, const QString &directory)
{
    ensure(QCoreApplication::translate("QPlatformTheme", "Save") == QStringLiteral("保存"), "Chinese standard buttons unavailable");
    // 标签自检独立于既有记录计数，覆盖删候选保历史、去重、重建及 v1 备份读取。
    Store tagged(QDir(directory).filePath("tag-check")); const auto taggedBook = tagged.addLedger("标签测试");
    tagged.addTag(" 学习 "); tagged.addTag("学习"); ensure(tagged.entities("tag").size() == 1, "tag dedup failed");
    tagged.start(taggedBook, "带标签任务", "学习"); tagged.finish(tagged.active()["started"].toInteger() + 1000);
    tagged.deleteTag("学习"); ensure(tagged.entities("tag").isEmpty(), "tag deletion failed");
    ensure(tagged.entities("record")[0].toObject()["tag"].toString() == "学习" && tagged.csv().contains("学习"), "historical tag lost");
    const auto taggedBackup = tagged.backup(); Store restored(QDir(directory).filePath("tag-restored")); restored.importBackup(taggedBackup);
    ensure(restored.entities("tag").isEmpty() && restored.entities("record")[0].toObject()["tag"].toString() == "学习", "tag backup lost");
    restored.addTag("学习"); ensure(restored.entities("tag").size() == 1, "tag re-add failed");
    const auto savedRecord = restored.entities("record")[0].toObject();
    restored.editRecord(savedRecord["id"].toString(), taggedBook, "清空标签", savedRecord["started"].toInteger(), savedRecord["ended"].toInteger(), {}, QStringLiteral(""));
    ensure(!restored.entities("record")[0].toObject().contains("tag"), "explicit tag clear failed");
    auto oldBackup = taggedBackup; oldBackup["formatVersion"] = 1; oldBackup.remove("tags");
    auto oldRecords = oldBackup["records"].toArray(); auto oldRecord = oldRecords[0].toObject(); oldRecord.remove("tag"); oldRecords[0] = oldRecord; oldBackup["records"] = oldRecords;
    Store legacy(QDir(directory).filePath("tag-legacy")); legacy.importBackup(oldBackup); ensure(!legacy.entities("record")[0].toObject().contains("tag"), "v1 backup compatibility failed");
    const auto one = store.addLedger(QStringLiteral("工作")), two = store.addLedger(QStringLiteral("生活"));
    store.start(one, QStringLiteral("中文，逗号\n下一行"));
    bool blocked = false; try { store.addLedger("不允许"); } catch (...) { blocked = true; } ensure(blocked, "active lock failed");
    const auto active = store.active(); store.finish(active["started"].toInteger() + 90061000);
    store.editRecord({}, two, "=表格公式内容", 1700000000000LL, 1700000001000LL);
    const auto backup = store.backup(); ensure(backup["records"].toArray().size() == 2, "ledger isolation failed");
    ensure(store.importBackup(backup)["added"].toInt() == 0, "duplicate import failed");
    auto bad = backup; auto rows = backup["records"].toArray(); auto changed = rows[0].toObject(); changed["id"] = "new-valid"; rows.append(changed); rows.append(QJsonObject{{"id", "bad"}}); bad["records"] = rows;
    bool rejected = false; try { store.importBackup(bad); } catch (...) { rejected = true; }
    ensure(rejected && store.entities("record").size() == 2, "import rollback failed");
    auto unsupported = backup; auto unsupportedRows = backup["records"].toArray();
    auto future = unsupportedRows.first().toObject(); future["futureField"] = "unsupported"; unsupportedRows[0] = future; unsupported["records"] = unsupportedRows;
    rejected = false; try { store.importBackup(unsupported); } catch (...) { rejected = true; }
    ensure(rejected && store.entities("record").size() == 2, "unknown field was accepted");
    auto conflicted = backup; rows = backup["records"].toArray(); changed = rows[0].toObject(); changed["task"] = "另一版本"; rows[0] = changed; conflicted["records"] = rows;
    ensure(store.importBackup(conflicted)["conflicts"].toInt() == 1, "conflict preservation failed");
    ensure(store.importBackup(conflicted)["conflicts"].toInt() == 0, "conflict dedup failed");
    store.deleteLedger(one); ensure(store.entities("record").size() == 1, "ledger delete isolation failed");
    store.importBackup(backup); ensure(store.entities("record").size() == 1, "deleted record resurrected");
    ensure(durationText(90061000) == "25:01:01", "long duration failed");
    ensure(store.csv(two).contains("'=表格公式内容"), "CSV escaping failed");
    // 使用需要 JSON 转义的长文字，验证批次按真实字节数拆分，不能堵在服务器大小上限。
    Store batch(QDir(directory).filePath("batch-check")); const auto book = batch.addLedger("批次检查");
    QJsonArray largeRows; const auto longTask = "a" + QString(9999, QChar(1));
    for (int i = 0; i < 50; ++i) largeRows.append(QJsonObject{{"id", QUuid::createUuid().toString(QUuid::WithoutBraces)}, {"ledgerId", book}, {"task", longTask}, {"started", 1700000000000LL}, {"ended", 1700000001000LL}, {"deleted", false}, {"revision", 1}});
    batch.importBackup({{"format", "lyushi.backup"}, {"formatVersion", 1}, {"ledgers", batch.entities("ledger")}, {"records", largeRows}, {"conflicts", QJsonArray{}}});
    const auto request = batch.syncRequest();
    ensure(request["operations"].toArray().size() < 50 && request["operations"].toArray().size() > 0 && QJsonDocument(request).toJson(QJsonDocument::Compact).size() < 2 * 1024 * 1024, "large operation batch exceeds request limit");
}

int main(int argc, char **argv)
{
    QApplication app(argc, argv); app.setOrganizationName("Lyushi"); app.setApplicationName("Lyushi"); app.setApplicationVersion("0.4.0");
    QLocale::setDefault(QLocale(QLocale::Chinese, QLocale::China));
    SimplifiedChineseTranslator translator; app.installTranslator(&translator);
    // 命令行检查和截图的设置也使用隔离目录，不覆盖日常使用时选择的账本或主题。
    if (app.arguments().size() > 2 && app.arguments().value(1) != "--configure") { QSettings::setDefaultFormat(QSettings::IniFormat); QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, QDir(app.arguments().at(2)).filePath("settings")); QStandardPaths::setTestModeEnabled(true); }
    applyTheme();
    try {
        const auto args = app.arguments(); const auto mode = args.value(1);
        if (mode == "--configure") {
            QFile key(args.value(3)); ensure(key.open(QIODevice::ReadOnly) && key.size() <= 256, "cannot read device key");
            const auto secret = QString::fromUtf8(key.readAll()).trimmed(); const QUrl address(args.value(2));
            ensure(address.scheme() == "https" && !address.host().isEmpty() && address.userInfo().isEmpty(), "invalid server address");
            Vault::write(secret); QSettings().setValue("server", address.toString()); return 0;
        }
        const auto directory = args.value(2, QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation));
        QLockFile lock(QDir(directory).filePath("instance.lock")); QDir().mkpath(directory);
        ensure(lock.tryLock(0), QStringLiteral("此数据目录已由另一个柳时进程打开")); Store store(directory);
        if (mode == "--self-test") { selfTest(store, directory); puts("PASS: ledgers, active lock, import validation/rollback/conflicts, tombstones, CSV, bounded batches"); return 0; }
        if (mode == "--ui-lock-test") {
            QWidget owner; QDialog outer(&owner), inner(&outer); outer.setModal(true); inner.setModal(true);
            outer.show(); inner.show(); QCoreApplication::processEvents();
            ensure(outer.isVisible() && inner.isVisible(), "test dialogs did not open");
            closeManagementDialogs(&owner);
            ensure(!outer.isVisible() && !inner.isVisible(), "nested management dialogs remained open");
            puts("PASS: nested edit and ledger dialogs close on remote active state"); return 0;
        }
        if (mode == "--seed-active") { const auto id = store.addLedger("恢复检查"); store.start(id, "跨进程恢复"); return 0; }
        if (mode == "--resume-active") { ensure(store.active()["task"].toString() == "跨进程恢复", "restore failed"); store.finish(QDateTime::currentMSecsSinceEpoch()); return 0; }
        // 互通检查使用隔离目录，验证安卓与桌面的真实备份文件可以互相读取。
        if (mode == "--import") {
            QFile input(args.value(3)); ensure(input.open(QIODevice::ReadOnly) && input.size() <= 32 * 1024 * 1024, "cannot read backup");
            QJsonParseError error; const auto document = QJsonDocument::fromJson(input.readAll(), &error);
            ensure(error.error == QJsonParseError::NoError && document.isObject(), "invalid JSON");
            puts(QJsonDocument(store.importBackup(document.object())).toJson(QJsonDocument::Compact).constData()); return 0;
        }
        if (mode == "--export") { writeFile(args.value(3), QJsonDocument(store.backup()).toJson()); return 0; }
        if (mode == "--inspect") { writeFile(args.value(3), QJsonDocument(QJsonObject{{"tags", store.entities("tag", true)}, {"ledgers", store.entities("ledger", true)}, {"records", store.entities("record", true)}, {"conflicts", store.conflicts()}, {"pending", store.pendingCount()}}).toJson()); return 0; }
        // 本地依赖检查不访问网络、不读取设备密钥，可在整理后的安装包中直接执行。
        if (mode == "--capabilities") {
            writeFile(args.value(3), QJsonDocument(QJsonObject{{"qt", qVersion()}, {"tls", QSslSocket::supportsSsl()}, {"tlsBackend", QSslSocket::activeBackend()}, {"tlsBackends", QJsonArray::fromStringList(QSslSocket::availableBackends())}}).toJson()); return QSslSocket::supportsSsl() ? 0 : 1;
        }
        // 隔离测试和自动化入口仍通过领域方法修改，不能绕过记录期间限制或时间校验。
        if (mode == "--command") {
            QFile input(args.value(3)); ensure(input.open(QIODevice::ReadOnly) && input.size() <= 1024 * 1024, "invalid command file");
            const auto command = QJsonDocument::fromJson(input.readAll()).object(); const auto action = command["action"].toString();
            if (action == "edit") store.editRecord(command["id"].toString(), command["ledgerId"].toString(), command["task"].toString(), command["started"].toInteger(), command["ended"].toInteger());
            else if (action == "delete") store.deleteRecord(command["id"].toString());
            else if (action == "add-tag") store.addTag(command["name"].toString());
            else if (action == "delete-tag") store.deleteTag(command["name"].toString());
            else if (action == "tag-record") store.editRecord(command["id"].toString(), command["ledgerId"].toString(), command["task"].toString(), command["started"].toInteger(), command["ended"].toInteger(), {}, command["tag"].toString());
            else if (action == "delete-ledger") store.deleteLedger(command["id"].toString());
            else if (action == "finish") store.finish(QDateTime::currentMSecsSinceEpoch());
            else ensure(false, "unsupported command"); return 0;
        }
        if (mode == "--sync-once") {
            QFile file(args.value(4)); ensure(file.open(QIODevice::ReadOnly) && file.size() <= 256, "cannot read test device key");
            SyncClient sync(store); sync.settled = [&](bool success) { puts(sync.status.toUtf8().constData()); app.exit(success ? 0 : 1); };
            QTimer::singleShot(0, [&] { try { sync.configure(args.value(3), QString::fromUtf8(file.readAll()).trimmed(), true); } catch (...) { app.exit(1); } });
            QTimer::singleShot(60000, [&] { app.exit(2); }); return app.exec();
        }
        Window window(store); window.show();
        if (mode == "--snapshot") QTimer::singleShot(700, [&] { app.exit(window.grab().save(args.value(3)) ? 0 : 1); });
        return app.exec();
    } catch (const std::exception &error) { fprintf(stderr, "%s\n", error.what()); return 1; }
}
