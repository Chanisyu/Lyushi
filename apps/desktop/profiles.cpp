#include "profiles.h"
#include "vault.h"
#include <QtWidgets>
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QEventLoop>
#include <QTimer>

QString accountProfile(){ return QSettings().value("accountProfile","local").toString(); }
QString accountDirectory(const QString &profile){ensure(profile=="local"||QRegularExpression("^[0-9a-f]{64}$").match(profile).hasMatch(),QStringLiteral("账户数据空间无效"));return QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation)+"/profiles/"+profile;}
QString accountServer(const QString &profile){return QSettings().value("accounts/"+profile+"/server","").toString();}

/** 握手不携带业务数据；标准系统证书校验与禁止重定向保持不变。 */
QJsonObject identifyAccount(const QString &address,const QString &key){
 const QUrl base(address);ensure(base.isValid()&&base.scheme()=="https"&&!base.host().isEmpty()&&base.userInfo().isEmpty()&&!base.hasQuery()&&!base.hasFragment(),QStringLiteral("请输入有效 HTTPS 服务器地址"));
 ensure(QRegularExpression("^[0-9a-f]{64}$").match(key).hasMatch(),QStringLiteral("设备密钥须为 64 位小写十六进制文字"));
 QNetworkAccessManager manager;QNetworkRequest request(QUrl(address+"/v1/device"));request.setRawHeader("Authorization","Bearer "+key.toUtf8());request.setRawHeader("X-Lyushi-Protocol","4");request.setTransferTimeout(15000);request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,QNetworkRequest::ManualRedirectPolicy);
 auto *reply=manager.get(request);QEventLoop loop;QObject::connect(reply,&QNetworkReply::finished,&loop,&QEventLoop::quit);loop.exec();
 ensure(reply->error()==QNetworkReply::NoError&&reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt()==200,QStringLiteral("账户验证失败，请核对服务器地址、设备密钥及账户状态"));
 const auto raw=reply->readAll();ensure(raw.size()<=16384,QStringLiteral("账户响应过大"));const auto info=QJsonDocument::fromJson(raw).object();
 for(const auto &field:{"userId","serverId"})ensure(QRegularExpression("^[0-9a-f]{64}$").match(info[field].toString()).hasMatch(),QStringLiteral("账户身份格式无效"));return info;
}

/** 外观和已有账户可离线切换；连接新密钥时先验证归属，再决定是否合并单机数据。 */
bool showAccountSettings(QWidget *owner,Store &store,SyncClient &sync){
 QDialog dialog(owner);dialog.setWindowTitle(QStringLiteral("设置"));QFormLayout form(&dialog);form.addRow(QStringLiteral("版本"),new QLabel(QCoreApplication::applicationVersion()));form.addRow(QStringLiteral("语言"),new QLabel(QStringLiteral("简体中文")));
 QComboBox theme;theme.addItems({QStringLiteral("跟随系统"),QStringLiteral("白天"),QStringLiteral("黑夜")});theme.setCurrentIndex(QSettings().value("theme",0).toInt());form.addRow(QStringLiteral("主题"),&theme);
 QComboBox profiles;profiles.addItem(QStringLiteral("单机数据"),"local");auto saved=QSettings().value("accountProfiles").toStringList();for(const auto &id:saved)profiles.addItem(QSettings().value("accounts/"+id+"/name",id.left(12)).toString(),id);profiles.setCurrentIndex(qMax(0,profiles.findData(accountProfile())));form.addRow(QStringLiteral("已保存账户"),&profiles);
 QPushButton switchButton(QStringLiteral("切换至所选数据空间"));form.addRow(&switchButton);
 QLineEdit server(accountServer(accountProfile())),key;server.setPlaceholderText("https://");key.setEchoMode(QLineEdit::Password);key.setPlaceholderText(QStringLiteral("填写后台生成的设备密钥"));form.addRow(QStringLiteral("服务器地址"),&server);form.addRow(QStringLiteral("设备密钥"),&key);
 QLabel note(QStringLiteral("新连接先验证账户；各账户的账本、队列和密钥独立保存。记录期间禁止切换账户。"));note.setWordWrap(true);form.addRow(&note);
 try{key.setText(Vault::read(accountProfile()));}catch(const std::exception &error){note.setText(QString::fromUtf8(error.what()));}
 QPushButton connectButton(QStringLiteral("验证并连接账户"));form.addRow(&connectButton);bool changed=false;
 const bool idle=store.active().isEmpty();profiles.setEnabled(idle);switchButton.setEnabled(idle);server.setEnabled(idle);key.setEnabled(idle);connectButton.setEnabled(idle);
 auto safely=[&](const std::function<void()> &fn){try{fn();}catch(const std::exception &error){QMessageBox::warning(&dialog,QStringLiteral("操作未完成"),QString::fromUtf8(error.what()));}};
 auto activate=[&](const QString &profile){ensure(store.active().isEmpty()&&sync.canConfigure(),QStringLiteral("请先结束记录或等待当前同步完成"));const auto secret=Vault::read(profile);sync.configure({},{});store.useDirectory(accountDirectory(profile));QSettings().setValue("accountProfile",profile);sync.configure(accountServer(profile),secret);changed=true;dialog.accept();};
 QObject::connect(&switchButton,&QPushButton::clicked,&dialog,[&]{safely([&]{activate(profiles.currentData().toString());});});
 QObject::connect(&connectButton,&QPushButton::clicked,&dialog,[&]{safely([&]{
  ensure(store.active().isEmpty()&&sync.canConfigure(),QStringLiteral("请先结束记录或等待当前同步完成"));auto address=server.text().trimmed();while(address.endsWith('/'))address.chop(1);const auto secret=key.text().trimmed();const auto info=identifyAccount(address,secret);
  ensure(store.active().isEmpty()&&sync.canConfigure(),QStringLiteral("记录状态发生变化，请稍后重试"));
  const auto id=QString::fromLatin1(QCryptographicHash::hash((address+"\n"+info["userId"].toString()+"\n"+info["serverId"].toString()).toUtf8(),QCryptographicHash::Sha256).toHex());
  QJsonObject backup;
  if(accountProfile()=="local"&&!store.entities("ledger",true).isEmpty()){const auto answer=QMessageBox::question(&dialog,QStringLiteral("首次连接账户"),QStringLiteral("连接账户“%1”。是否将当前单机数据合并到该账户？选择否将使用账户独立数据，单机数据仍保留。").arg(info["displayName"].toString()),QMessageBox::Yes|QMessageBox::No|QMessageBox::Cancel,QMessageBox::No);if(answer==QMessageBox::Cancel)return;if(answer==QMessageBox::Yes)backup=store.backup();}
  sync.configure({},{});try{
   // 先在临时连接完成目标校验和导入，失败时当前窗口仍持有原账户数据库。
   if(accountDirectory(id)==store.directory()){store.bindAccount(info["userId"].toString(),info["serverId"].toString());Vault::write(secret,id);}
   else {{Store target(accountDirectory(id));target.bindAccount(info["userId"].toString(),info["serverId"].toString());if(!backup.isEmpty())target.importBackup(backup);Vault::write(secret,id);}store.useDirectory(accountDirectory(id));}
  }catch(...){sync.configure(accountServer(accountProfile()),Vault::read(accountProfile()));throw;}
  QSettings settings;settings.setValue("accounts/"+id+"/server",address);settings.setValue("accounts/"+id+"/name",info["displayName"].toString()+" · "+info["username"].toString());if(!saved.contains(id))saved.append(id);settings.setValue("accountProfiles",saved);settings.setValue("accountProfile",id);settings.sync();ensure(settings.status()==QSettings::NoError,QStringLiteral("账户设置保存失败"));sync.configure(address,secret);changed=true;dialog.accept();
 });});
 QDialogButtonBox buttons(QDialogButtonBox::Save|QDialogButtonBox::Close);form.addRow(&buttons);QObject::connect(&buttons,&QDialogButtonBox::rejected,&dialog,&QDialog::reject);QObject::connect(&buttons,&QDialogButtonBox::accepted,&dialog,[&]{QSettings settings;settings.setValue("theme",theme.currentIndex());settings.sync();changed=true;dialog.accept();});dialog.exec();return changed;
}
