#include "waterui.h"
#include "store.h"
#include "syncclient.h"
#include <QtWidgets>
#include <QJsonDocument>
#include <QPainterPath>
#include <QSaveFile>
#include <QTimeZone>
#include <algorithm>

namespace {
constexpr int maxWater = 10000;
const auto zone = QTimeZone::fromSecondsAheadOfUtc(8*3600);

QString labelFor(const QString &kind)
{
    const QMap<QString,QString> names{{"fill","加水"},{"drink","喝水"},{"pour","倒水"},{"manual","补记"},{"setup","设置水壶"},
        {"capacity","修改容量"},{"calibrate","校准水位"},{"undo","撤销"},{"correct","更正"},{"void","作废"},{"restore-entry","合并历史"},{"restore-bottle","恢复水壶"}};
    return names.value(kind,QStringLiteral("饮水操作"));
}

bool counts(const QJsonObject &entry)
{
    return !entry["voided"].toBool() && (entry["kind"] == "drink" || entry["kind"] == "manual");
}

QDateTime beijingTime(qint64 at) { return QDateTime::fromMSecsSinceEpoch(at,zone); }
QString mlText(int ml) { return QStringLiteral("%1 mL").arg(ml); }

QList<QJsonObject> orderedEntries(const QJsonObject &view)
{
    QList<QJsonObject> rows;
    for (const auto &value : view["entries"].toObject()) rows.append(value.toObject());
    std::sort(rows.begin(),rows.end(),[](const auto &a,const auto &b) { return a["at"].toInteger() > b["at"].toInteger(); });
    return rows;
}

/** 装饰盖、提环和瓶肩在刻度之外。画面和手势共用同一有效高度，避免换算偏差。 */
class BottleControl : public QWidget {
    int capacity = 0, original = 0, level = 0;
    QString mode;
    bool dragging = false;

    QRectF body() const { return {width()/2.0-62,55,124,qMax(30,height()-72)*1.0}; }
    void moveLevel(double y)
    {
        if (capacity == 0 || mode.isEmpty()) return;
        const auto bounds = body();
        const int target = qRound(qBound(0.0,(bounds.bottom()-y)/bounds.height(),1.0)*capacity);
        setLevel(mode == "fill" ? qMax(original,target) : qMin(original,target));
    }
    void setLevel(int target)
    {
        level = qBound(mode == "fill" ? original : 0,target,mode == "fill" ? capacity : original);
        update();
        if (moved) moved(level);
    }
public:
    std::function<void(int)> moved;
    explicit BottleControl(QWidget *parent) : QWidget(parent)
    {
        setMinimumHeight(225); setSizePolicy(QSizePolicy::Expanding,QSizePolicy::Expanding);
        setFocusPolicy(Qt::StrongFocus);
        setAccessibleName(QStringLiteral("水壶水位"));
        setAccessibleDescription(QStringLiteral("选择操作后拖动蓝线，或使用方向键调整水量"));
    }
    void drawState(int maximum,int baseline,int value,const QString &selected)
    {
        capacity = maximum; original = baseline; level = value; mode = selected;
        setCursor(mode.isEmpty() ? Qt::ArrowCursor : Qt::SizeVerCursor);
        update();
    }
protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this); p.setRenderHint(QPainter::Antialiasing);
        const auto b = body(); const double x = b.center().x();
        const QColor outline = palette().color(QPalette::Mid);
        p.setPen(QPen(outline,2)); p.setBrush(palette().color(QPalette::AlternateBase));
        p.drawRoundedRect(QRectF(x+21,9,30,29),12,12); // 提环和螺旋盖标出便携水壶的轮廓。
        p.drawRoundedRect(QRectF(x-30,19,60,28),8,8);
        for (int i=-18;i<=18;i+=9) p.drawLine(QPointF(x+i,24),QPointF(x+i,37));
        QPainterPath shape;
        shape.moveTo(x-25,46); shape.lineTo(x+25,46);
        shape.quadTo(x+27,53,b.right()-12,b.top()+8);
        shape.quadTo(b.right(),b.top()+12,b.right(),b.top()+24);
        shape.lineTo(b.right(),b.bottom()-16);
        shape.quadTo(b.right(),b.bottom(),b.right()-16,b.bottom());
        shape.lineTo(b.left()+16,b.bottom());
        shape.quadTo(b.left(),b.bottom(),b.left(),b.bottom()-16);
        shape.lineTo(b.left(),b.top()+24);
        shape.quadTo(b.left(),b.top()+12,b.left()+12,b.top()+8);
        shape.quadTo(x-27,53,x-25,46); shape.closeSubpath();
        p.fillPath(shape,palette().color(QPalette::Base));
        const double y = b.bottom() - b.height()*(capacity ? double(level)/capacity : 0);
        p.save(); p.setClipPath(shape);
        QLinearGradient blue(b.topLeft(),b.bottomLeft());
        blue.setColorAt(0,QColor("#67c8f5")); blue.setColorAt(1,QColor("#268adb"));
        p.fillRect(QRectF(b.left(),y,b.width(),b.bottom()-y),blue);
        p.setPen(QPen(QColor("#176fb6"),3)); p.drawLine(QPointF(b.left(),y),QPointF(b.right(),y));
        p.restore(); p.setPen(QPen(outline,2)); p.setBrush(Qt::NoBrush); p.drawPath(shape);
        for (int i=0;i<=4;++i) {
            const double tick = b.bottom()-b.height()*i/4;
            p.drawLine(QPointF(b.right()+9,tick),QPointF(b.right()+16,tick));
        }
        if (!mode.isEmpty()) {
            p.setPen(QPen(palette().color(QPalette::Base),2)); p.setBrush(QColor("#176fb6"));
            p.drawEllipse(QPointF(x,qBound(b.top()+7,y,b.bottom()-6)),7,7);
        }
    }
    void mousePressEvent(QMouseEvent *event) override
    {
        if (event->button()!=Qt::LeftButton || mode.isEmpty() || !body().adjusted(-15,-9,15,9).contains(event->position())) return;
        dragging = true; moveLevel(event->position().y());
    }
    void mouseMoveEvent(QMouseEvent *event) override { if (dragging) moveLevel(event->position().y()); }
    void mouseReleaseEvent(QMouseEvent *event) override
    {
        if (dragging) { dragging = false; moveLevel(event->position().y()); }
    }
    void keyPressEvent(QKeyEvent *event) override
    {
        if (!mode.isEmpty() && (event->key()==Qt::Key_Up || event->key()==Qt::Key_Down)) {
            setLevel(level+(event->key()==Qt::Key_Up ? 1 : -1)); return;
        }
        QWidget::keyPressEvent(event);
    }
};

class WaterDialog : public QDialog {
    friend void checkWaterDialog(WaterDialog &,Store &);
    Store &store;
    SyncClient &sync;
    QJsonObject bottle, draftBottle;
    QString mode;
    int draftML = 0;
    bool stale = false;
    BottleControl *drawing;
    QLabel *today, *remaining, *preview, *status;
    QPushButton *confirm, *cancel, *capacity, *undo, *conflicts;
    QAction *calibrate;
    QMap<QString,QPushButton *> modes;
    QTimer midnight;

    /** 数据错误保留表单和输入，确认成功之后才关闭预览并发起上传。 */
    void attempt(const std::function<void()> &fn)
    {
        try { fn(); } catch (const std::exception &error) { QMessageBox::warning(this,QStringLiteral("饮水操作未完成"),QString::fromUtf8(error.what())); }
    }
    void discard() { mode.clear(); draftBottle = {}; stale = false; }
    void submit(QJsonObject c)
    {
        store.waterSubmit(c); discard(); refresh(); sync.requestWater(true);
    }
    void showCapacity()
    {
        const auto captured = bottle;
        bool ok = false;
        const int value = QInputDialog::getInt(this,QStringLiteral("水壶容量"),QStringLiteral("容量（mL）"),
            captured["capacity"].toInt(500),1,maxWater,1,&ok);
        if (!ok) return;
        QJsonObject c{{"kind",captured.isEmpty() ? "setup" : "capacity"},{"capacity",value},{"ml",0}};
        if (!captured.isEmpty()) c["base"] = captured["version"];
        submit(c);
    }
    /** 按实物校准剩余量，既不创建虚构的加水或倒水记录，也不改变饮水统计。 */
    void showCalibration()
    {
        const auto captured = bottle;
        bool ok = false;
        const int value = QInputDialog::getInt(this,QStringLiteral("校准水位"),QStringLiteral("当前实际剩余量（mL）"),
            captured["ml"].toInt(),0,captured["capacity"].toInt(),1,&ok);
        if (ok) submit({{"kind","calibrate"},{"base",captured["version"]},{"ml",value}});
    }
    void showEdit(QDialog *parent,const QJsonObject &entry = {})
    {
        QDialog dialog(parent); dialog.setWindowTitle(entry.isEmpty() ? QStringLiteral("补记饮水") : QStringLiteral("更正饮水"));
        QFormLayout form(&dialog); QSpinBox amount; amount.setRange(1,maxWater); amount.setValue(entry["ml"].toInt(250)); amount.setSuffix(" mL");
        QDateTimeEdit at(beijingTime(entry["at"].toInteger(QDateTime::currentMSecsSinceEpoch())));
        at.setTimeZone(zone); at.setDisplayFormat("yyyy-MM-dd HH:mm:ss"); at.setCalendarPopup(true);
        form.addRow(QStringLiteral("饮水量"),&amount); form.addRow(QStringLiteral("时间（北京时间）"),&at);
        QDialogButtonBox buttons(QDialogButtonBox::Save|QDialogButtonBox::Cancel); form.addRow(&buttons);
        connect(&buttons,&QDialogButtonBox::rejected,&dialog,&QDialog::reject);
        connect(&buttons,&QDialogButtonBox::accepted,&dialog,[&] {
            attempt([&] {
                QJsonObject c{{"kind",entry.isEmpty() ? "manual" : "correct"},{"ml",amount.value()},{"at",at.dateTime().toMSecsSinceEpoch()}};
                if (!entry.isEmpty()) { c["target"] = entry["id"]; c["base"] = entry["version"]; }
                submit(c); dialog.accept();
            });
        });
        dialog.exec();
    }
    void showHistory()
    {
        QDialog dialog(this); dialog.setWindowTitle(QStringLiteral("饮水历史")); dialog.resize(520,410);
        QVBoxLayout layout(&dialog); QHBoxLayout actions; QDateEdit day(beijingTime(QDateTime::currentMSecsSinceEpoch()).date());
        day.setCalendarPopup(true); day.setDisplayFormat("yyyy-MM-dd");
        QPushButton back(QStringLiteral("前一天")), next(QStringLiteral("后一天")), add(QStringLiteral("补记饮水"));
        actions.addWidget(&back); actions.addWidget(&day); actions.addWidget(&next); actions.addStretch(); actions.addWidget(&add); layout.addLayout(&actions);
        QLabel summary; layout.addWidget(&summary);
        QTableWidget list; list.setColumnCount(4);
        list.setHorizontalHeaderLabels({QStringLiteral("北京时间"),QStringLiteral("类型"),QStringLiteral("水量"),QStringLiteral("状态")});
        list.horizontalHeader()->setSectionResizeMode(QHeaderView::Stretch);
        list.setSelectionBehavior(QAbstractItemView::SelectRows); list.setSelectionMode(QAbstractItemView::SingleSelection); list.setEditTriggers(QAbstractItemView::NoEditTriggers);
        layout.addWidget(&list,1);
        QPushButton edit(QStringLiteral("更正")), voidEntry(QStringLiteral("作废")); QHBoxLayout editBar; editBar.addWidget(&edit); editBar.addWidget(&voidEntry); layout.addLayout(&editBar);
        QList<QJsonObject> rows;
        auto reload = [&] {
            rows.clear(); list.setRowCount(0); qint64 total=0;
            for (const auto &entry : orderedEntries(store.waterView())) {
                const auto date = beijingTime(entry["at"].toInteger()); if (date.date()!=day.date()) continue;
                if (counts(entry)) total += entry["ml"].toInt();
                const auto index = rows.size(); rows.append(entry); list.insertRow(index);
                const QStringList values{date.toString("HH:mm:ss"),labelFor(entry["kind"].toString()),mlText(entry["ml"].toInt()),entry["voided"].toBool()?QStringLiteral("已作废"):QString{}};
                for (int i=0;i<values.size();++i) list.setItem(index,i,new QTableWidgetItem(values[i]));
            }
            summary.setText(QStringLiteral("当日饮水 %1 mL").arg(total));
            edit.setEnabled(false); voidEntry.setEnabled(false);
        };
        connect(&day,&QDateEdit::dateChanged,&dialog,[&] { attempt(reload); });
        connect(&back,&QPushButton::clicked,&dialog,[&] { day.setDate(day.date().addDays(-1)); });
        connect(&next,&QPushButton::clicked,&dialog,[&] { day.setDate(day.date().addDays(1)); });
        connect(&list,&QTableWidget::itemSelectionChanged,&dialog,[&] {
            const bool available = list.currentRow()>=0 && list.currentRow()<rows.size() && counts(rows[list.currentRow()]);
            edit.setEnabled(available); voidEntry.setEnabled(available);
        });
        connect(&add,&QPushButton::clicked,&dialog,[&] { attempt([&] { showEdit(&dialog); reload(); }); });
        connect(&edit,&QPushButton::clicked,&dialog,[&] { attempt([&] { showEdit(&dialog,rows.value(list.currentRow())); reload(); }); });
        connect(&voidEntry,&QPushButton::clicked,&dialog,[&] { attempt([&] {
            const auto entry=rows.value(list.currentRow());
            if (QMessageBox::question(&dialog,QStringLiteral("作废饮水"),QStringLiteral("作废后该条记录退出当日统计。确认作废？"))==QMessageBox::Yes)
                submit({{"kind","void"},{"target",entry["id"]},{"base",entry["version"]}});
            reload();
        }); });
        reload(); dialog.exec(); refresh();
    }
    void review()
    {
        QDialog dialog(this); dialog.setWindowTitle(QStringLiteral("待核对的饮水操作")); dialog.resize(480,320);
        QVBoxLayout layout(&dialog);
        auto *scroll = new QScrollArea; auto *content = new QWidget; auto *items = new QVBoxLayout(content);
        scroll->setWidget(content); scroll->setWidgetResizable(true); layout.addWidget(scroll);
        for (const auto &value : store.waterView()["conflicts"].toObject()) {
            const auto c = value.toObject()["command"].toObject(); const auto kind = c["kind"].toString();
            const auto caption = QStringLiteral("%1　%2　%3").arg(beijingTime(c["at"].toInteger()).toString("yyyy-MM-dd HH:mm"),labelFor(kind),
                kind=="fill" || kind=="drink" || kind=="pour" ? mlText(qAbs(c["from"].toInt()-c["ml"].toInt())) : QString{});
            items->addWidget(new QLabel(caption)); auto *row = new QHBoxLayout; items->addLayout(row);
            auto resolve = [&,c](const QString &resolution) {
                attempt([&] { submit({{"kind",resolution},{"target",c["id"]}}); dialog.accept(); });
            };
            if (kind == "drink") {
                auto *recover = new QPushButton(QStringLiteral("确实喝过，补记")); row->addWidget(recover);
                connect(recover,&QPushButton::clicked,&dialog,[resolve] { resolve("recover"); });
            }
            auto *ignore = new QPushButton(QStringLiteral("标记已核对")); row->addWidget(ignore);
            connect(ignore,&QPushButton::clicked,&dialog,[resolve] { resolve("dismiss"); });
        }
        layout.addWidget(new QLabel(QStringLiteral("核对操作只改变历史统计。请按实际剩余量重新调整水位。")));
        dialog.exec(); refresh();
    }
    void backupFile(bool import,bool csv)
    {
        const auto path = import ? QFileDialog::getOpenFileName(this,QStringLiteral("合并饮水备份"),{},QStringLiteral("JSON (*.json)"))
            : QFileDialog::getSaveFileName(this,QStringLiteral("导出饮水记录"),csv?QStringLiteral("饮水记录.csv"):QStringLiteral("饮水备份.json"),csv?QStringLiteral("CSV (*.csv)"):QStringLiteral("JSON (*.json)"));
        if (path.isEmpty()) return;
        if (import) {
            QFile file(path); ensure(file.open(QIODevice::ReadOnly) && file.size() <= 32*1024*1024,QStringLiteral("饮水备份不可读取或超过 32 MiB"));
            store.waterImport(QJsonDocument::fromJson(file.readAll()).object());
            discard(); refresh(); sync.requestWater(true); return;
        }
        QByteArray bytes;
        if (csv) {
            QString output=QStringLiteral("时间（北京时间）,类型,水量（mL）,计入饮水（mL）,状态\r\n");
            for (const auto &e : orderedEntries(store.waterView())) {
                const auto ml = e["ml"].toInt();
                output += QStringLiteral("%1,%2,%3,%4,%5\r\n").arg(beijingTime(e["at"].toInteger()).toString("yyyy-MM-dd HH:mm:ss"),labelFor(e["kind"].toString()),QString::number(ml),QString::number(counts(e)?ml:0),e["voided"].toBool()?QStringLiteral("已作废"):QStringLiteral("有效"));
            }
            bytes=QByteArray::fromHex("efbbbf")+output.toUtf8();
        } else bytes=QJsonDocument(store.waterBackup()).toJson();
        QSaveFile file(path); ensure(file.open(QIODevice::WriteOnly) && file.write(bytes)==bytes.size() && file.commit(),QStringLiteral("无法保存饮水文件"));
    }
    void previewText()
    {
        const auto current = mode.isEmpty() ? bottle : draftBottle;
        const int original=current["ml"].toInt(); const int ml=mode.isEmpty()?original:draftML;
        remaining->setText(current.isEmpty()?QStringLiteral("先设置水壶容量"):QStringLiteral("剩余 %1 / %2").arg(mlText(ml),mlText(current["capacity"].toInt())));
        preview->setText(stale?QStringLiteral("水壶已在其他设备更新。请取消预览，再依据最新水位操作。"):
            mode.isEmpty()?QStringLiteral("选择一种操作，然后拖动蓝色水位线"):
            QStringLiteral("%1 %2").arg(labelFor(mode),mlText(qAbs(ml-original))));
        confirm->setEnabled(!mode.isEmpty() && !stale && ml!=original);
        cancel->setEnabled(!mode.isEmpty());
    }
public:
    WaterDialog(QWidget *owner,Store &storage,SyncClient &client) : QDialog(owner),store(storage),sync(client)
    {
        setWindowTitle(QStringLiteral("饮水记录")); setProperty("waterIndependent",true); resize(435,535);
        auto *layout=new QVBoxLayout(this); layout->setContentsMargins(20,16,20,16);
        auto *headline=new QHBoxLayout; today=new QLabel; auto font=today->font(); font.setPointSize(18); font.setBold(true); today->setFont(font);
        headline->addWidget(today,1);
        auto *history=new QPushButton(QStringLiteral("历史")); headline->addWidget(history); layout->addLayout(headline);
        connect(history,&QPushButton::clicked,this,[&] { attempt([&] { showHistory(); }); });
        drawing=new BottleControl(this); layout->addWidget(drawing,1);
        remaining=new QLabel; remaining->setAlignment(Qt::AlignCenter); layout->addWidget(remaining);
        auto *row=new QHBoxLayout; layout->addLayout(row);
        for (const QString &kind : {QString("fill"),QString("drink"),QString("pour")}) {
            auto *button = new QPushButton(labelFor(kind)); button->setCheckable(true); modes[kind]=button; row->addWidget(button);
            connect(button,&QPushButton::clicked,this,[&,kind] { mode=kind; draftBottle=bottle; draftML=bottle["ml"].toInt(); stale=false; refresh(); });
        }
        preview=new QLabel; preview->setWordWrap(true); preview->setAlignment(Qt::AlignCenter); layout->addWidget(preview);
        auto *saveRow=new QHBoxLayout; layout->addLayout(saveRow);
        cancel=new QPushButton(QStringLiteral("取消预览")); confirm=new QPushButton(QStringLiteral("确认")); saveRow->addWidget(cancel); saveRow->addWidget(confirm);
        connect(cancel,&QPushButton::clicked,this,[&] { discard(); refresh(); });
        connect(confirm,&QPushButton::clicked,this,[&] { attempt([&] { submit({{"kind",mode},{"base",draftBottle["version"]},{"ml",draftML},{"from",draftBottle["ml"]}}); }); });
        drawing->moved=[&](int value) { draftML=value; previewText(); };
        auto *tools=new QHBoxLayout; layout->addLayout(tools);
        capacity=new QPushButton(QStringLiteral("水壶设置")); undo=new QPushButton(QStringLiteral("撤销上次"));
        auto *files=new QPushButton(QStringLiteral("备份与导出")); tools->addWidget(capacity); tools->addWidget(undo); tools->addWidget(files);
        auto *settings=new QMenu(capacity); capacity->setMenu(settings);
        settings->addAction(QStringLiteral("水壶容量"),this,[&] { attempt([&] { showCapacity(); }); });
        calibrate=settings->addAction(QStringLiteral("校准水位"),this,[&] { attempt([&] { showCalibration(); }); });
        connect(undo,&QPushButton::clicked,this,[&] { attempt([&] {
            const auto version = bottle["version"];
            if (QMessageBox::question(this,QStringLiteral("撤销上次操作"),QStringLiteral("撤销后恢复上一步的水壶状态，并作废相应明细。是否继续？")) == QMessageBox::Yes)
                submit({{"kind","undo"},{"base",version}});
        }); });
        auto *menu=new QMenu(files); files->setMenu(menu);
        for (int i=0;i<3;++i) menu->addAction(QStringList{QStringLiteral("导出 JSON"),QStringLiteral("合并 JSON"),QStringLiteral("导出 CSV")}[i],this,[&,i] { attempt([&] { backupFile(i==1,i==2); }); });
        conflicts=new QPushButton; layout->addWidget(conflicts); connect(conflicts,&QPushButton::clicked,this,[&] { attempt([&] { review(); }); });
        status=new QLabel; status->setWordWrap(true); layout->addWidget(status);
        connect(&midnight,&QTimer::timeout,this,[&] { attempt([&] { refresh(); }); }); midnight.start(30000);
        refresh();
    }
    void refresh()
    {
        const auto view=store.waterView(); bottle=view["bottle"].toObject();
        const auto day = beijingTime(QDateTime::currentMSecsSinceEpoch()).date(); qint64 total=0;
        for (const auto &value : view["entries"].toObject()) {
            const auto e=value.toObject(); if (counts(e) && beijingTime(e["at"].toInteger()).date()==day) total+=e["ml"].toInt();
        }
        today->setText(QStringLiteral("今日饮水 %1 mL").arg(total));
        if (!mode.isEmpty() && draftBottle["version"]!=bottle["version"]) stale=true;
        auto current=mode.isEmpty()?bottle:draftBottle;
        drawing->drawState(current["capacity"].toInt(),current["ml"].toInt(),mode.isEmpty()?current["ml"].toInt():draftML,stale?QString{}:mode);
        for (auto it=modes.cbegin();it!=modes.cend();++it) {
            it.value()->setChecked(it.key()==mode);
            it.value()->setEnabled(!bottle.isEmpty() && (it.key()=="fill" ? bottle["ml"].toInt()<bottle["capacity"].toInt() : bottle["ml"].toInt()>0));
        }
        previewText(); capacity->setEnabled(mode.isEmpty()); undo->setEnabled(mode.isEmpty() && bottle["undo"].isObject());
        calibrate->setEnabled(!bottle.isEmpty());
        const auto pending=view["pending"].toArray().size(); const auto count=view["conflicts"].toObject().size();
        status->setText(sync.waterStatus+(pending?QStringLiteral(" · %1 项待同步").arg(pending):QString{}));
        conflicts->setText(QStringLiteral("核对 %1 项饮水操作").arg(count)); conflicts->setVisible(count>0);
    }
};

/** 向真实控件发送指针事件，覆盖三种方向、预览与保存，不依赖网络或日常数据。 */
void checkWaterDialog(WaterDialog &dialog,Store &store)
{
    auto drag = [&](const QString &kind,double fraction) {
        auto *view=dialog.drawing; dialog.modes[kind]->click();
        const QPointF at(view->width()/2.0,view->height()-17-(view->height()-72)*fraction);
        QMouseEvent press(QEvent::MouseButtonPress,at,at,Qt::LeftButton,Qt::LeftButton,Qt::NoModifier);
        QMouseEvent release(QEvent::MouseButtonRelease,at,at,Qt::LeftButton,Qt::NoButton,Qt::NoModifier);
        QApplication::sendEvent(view,&press); QApplication::sendEvent(view,&release);
    };
    auto ml = [&] { return store.waterView()["bottle"].toObject()["ml"].toInt(); };
    const auto active=store.active();
    drag("fill",0.8); ensure(ml()==0 && dialog.draftML==600 && dialog.confirm->isEnabled(),"fill preview changed stored data");
    dialog.confirm->click(); ensure(ml()==600,"fill not saved");
    drag("fill",0.4); ensure(dialog.draftML==600 && !dialog.confirm->isEnabled(),"fill allowed a decrease");
    dialog.cancel->click(); drag("drink",0.95); ensure(dialog.draftML==600 && !dialog.confirm->isEnabled(),"drink allowed an increase");
    drag("drink",0.6); dialog.confirm->click(); ensure(ml()==450,"drink not saved");
    drag("pour",0.9); ensure(dialog.draftML==450 && !dialog.confirm->isEnabled(),"pour allowed an increase");
    drag("pour",0.4); dialog.confirm->click(); ensure(ml()==300 && dialog.today->text().contains("150 mL"),"pour changed drinking total");
    drag("drink",0.3);
    store.waterSubmit({{"kind","calibrate"},{"base",store.waterView()["bottle"].toObject()["version"]},{"ml",250}});
    dialog.refresh(); ensure(dialog.stale && !dialog.confirm->isEnabled() && ml()==250,"remote update allowed stale water preview");
    dialog.cancel->click();
    store.waterSubmit({{"kind","undo"},{"base",store.waterView()["bottle"].toObject()["version"]}});
    dialog.refresh(); ensure(ml()==300 && active==store.active(),"undo changed timer or failed to restore water");
}
}

void showWater(QWidget *owner, Store &store, SyncClient &sync)
{
    WaterDialog dialog(owner,store,sync);
    const auto previous=sync.waterUpdated;
    sync.waterUpdated=[&] { try { dialog.refresh(); } catch (const std::exception &) {} };
    sync.requestWater(); dialog.exec(); sync.waterUpdated=previous;
}

/** 存储与界面共同验收，备份合并在另一独立数据库执行。 */
void waterSelfTest(Store &store,const QString &snapshot)
{
    const auto book=store.addLedger(QStringLiteral("饮水计时检查")); store.start(book,QStringLiteral("持续计时"));
    store.waterSubmit({{"kind","setup"},{"capacity",750},{"ml",0}});
    SyncClient sync(store); WaterDialog dialog(nullptr,store,sync); dialog.show(); QApplication::processEvents();
    checkWaterDialog(dialog,store);
    const auto backup=store.waterBackup();
    Store restored(store.directory()+"/restored"); restored.waterImport(backup); restored.waterImport(backup);
    const auto view=restored.waterView();
    ensure(view["bottle"].toObject()["ml"].toInt()==300 && view["entries"].toObject().size()==3,"water restore duplicated or lost records");
    if (!snapshot.isEmpty()) ensure(dialog.grab().save(snapshot),"water preview save failed");
}
