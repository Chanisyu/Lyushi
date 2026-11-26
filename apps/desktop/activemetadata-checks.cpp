#include "store.h"
#include <QDir>

/** 忽略版本外壳后逐字段对比，确保元数据更正没有重置时间、身份或分段。 */
static QJsonObject content(QJsonObject record) { record.remove("version"); record.remove("revision"); return record; }

/** 独立真实 SQLite 验证范围、事务、过期表单、恢复及标签并发删除。 */
void activeMetadataSelfTest(const QString &directory)
{
    auto rejects = [](const std::function<void()> &action) { bool failed = false; try { action(); } catch (...) { failed = true; } ensure(failed, "invalid active metadata edit accepted"); };
    const auto profile = QDir(directory).filePath("active-metadata"); QString id; QJsonObject saved;
    {
        Store store(profile); const auto book = store.addLedger("修改验收"); store.addTag("学习"); store.addTag("生活");
        store.start(book,"背单词","学习"); auto current = store.active(); id = current["id"].toString(); const auto start = current["started"].toInteger();
        auto edit = [&](const QString &name, const QString &tag) { store.editActiveMetadata(id,store.active()["version"].toString(),name,tag); };
        const auto pending = store.pendingCount();
        rejects([&] { store.editActiveMetadata("other",current["version"].toString(),"误改",{}); });
        rejects([&] { store.editActiveMetadata(id,"","误改",{}); });
        rejects([&] { edit("   ",{}); }); rejects([&] { edit(QString(10001,'x'),{}); }); rejects([&] { edit("更正","不存在"); });
        ensure(store.active() == current && store.pendingCount() == pending,"invalid edit changed state or queue");
        edit(" 背单词 ","学习"); ensure(store.active() == current && store.pendingCount() == pending,"no-op created a version");
        edit("复习单词",{}); auto expected = current; expected["task"] = "复习单词"; expected.remove("tag");
        ensure(content(store.active()) == content(expected) && store.pendingCount() == pending+1,"focus edit changed timing or queue count");
        rejects([&] { store.editActiveMetadata(id,current["version"].toString(),"旧表单",{}); });
        current = store.active(); store.distract("点饭","生活",start+1000,current["version"].toString()); current = store.active();
        edit("选午餐",{}); expected = current; auto parts = taskSegments(expected); auto last = parts.last().toObject(); last["task"] = "选午餐"; last.remove("tag"); parts[parts.size()-1] = last; expected["segments"] = parts;
        ensure(content(store.active()) == content(expected),"distraction edit changed root or times");
        current = store.active(); store.resumeFocus(start+2000,current["version"].toString());
        rejects([&] { store.editActiveMetadata(id,current["version"].toString(),"过期分心","生活"); });
        current = store.active(); edit("英语复习","学习"); expected = current; expected["task"] = "英语复习"; expected["tag"] = "学习";
        ensure(content(store.active()) == content(expected) && segmentRecord(store.active(),0)["task"] == "英语复习" && segmentRecord(store.active(),2)["tag"] == "学习" && segmentRecord(store.active(),1)["task"] == "选午餐","focus metadata scope invalid");
        current = store.active(); store.distract("接电话","生活",start+3000,current["version"].toString()); current = store.active(); edit("回复电话","生活");
        ensure(segmentRecord(store.active(),1)["task"] == "选午餐" && segmentRecord(store.active(),3)["task"] == "回复电话","earlier distraction overwritten");
        saved = store.active();
    }
    {
        Store store(profile); ensure(store.active() == saved,"restart lost saved metadata");
        store.finish(saved["started"].toInteger()+4000,saved["version"].toString());
        const auto ended = store.entities("record")[0].toObject(); const int pending = store.pendingCount();
        rejects([&] { store.editActiveMetadata(id,saved["version"].toString(),"重新启动",{}); });
        ensure(store.active().isEmpty() && store.entities("record")[0] == ended && store.pendingCount() == pending,"stale form restarted ended task");
        Store restored(QDir(directory).filePath("active-metadata-restored")); restored.importBackup(store.backup());
        ensure(content(restored.entities("record")[0].toObject()) == content(ended),"backup lost edited metadata");
        store.start(ended["ledgerId"].toString(),"另一任务"); const auto other = store.active();
        rejects([&] { store.editActiveMetadata(id,saved["version"].toString(),"误改",{}); }); ensure(store.active()==other,"stale form edited new task");
    }
    {
        Store store(QDir(directory).filePath("active-tag-race")); const auto book = store.addLedger("标签并发"); store.addTag("学习"); store.addTag("生活"); store.start(book,"背单词","学习");
        // 先确认本机队列，再投递远端标签墓碑；活动任务版本本身保持不变。
        const auto sent = store.syncRequest()["operations"].toArray(); QJsonArray ack, changes; int seq=0;
        for (const auto &value : sent) { const auto op=value.toObject(); ack.append(op["id"]); changes.append(QJsonObject{{"seq",++seq},{"kind",op["kind"]},{"id",op["entityId"]},{"heads",QJsonArray{op["entity"]}}}); }
        store.acceptSync({{"serverId","tag-race"},{"cursor",seq},{"ack",ack},{"changes",changes},{"more",false}},sent);
        for (const auto &value : store.entities("tag")) { auto tag=value.toObject(); tag["deleted"]=true; tag["version"]="removed-"+tag["name"].toString();
            store.acceptSync({{"serverId","tag-race"},{"cursor",++seq},{"ack",QJsonArray{}},{"more",false},{"changes",QJsonArray{QJsonObject{{"seq",seq},{"kind","tag"},{"id",tag["id"]},{"heads",QJsonArray{tag}}}}}},{});
        }
        auto current=store.active(); const auto pending=store.pendingCount();
        rejects([&] { store.editActiveMetadata(current["id"].toString(),current["version"].toString(),"更正","生活"); });
        ensure(store.active()==current && store.pendingCount()==pending,"deleted candidate changed record");
        store.editActiveMetadata(current["id"].toString(),current["version"].toString(),"保留快照","学习");
        ensure(store.active()["tag"]=="学习" && store.entities("tag").isEmpty(),"snapshot was lost or tag resurrected");
    }
}
