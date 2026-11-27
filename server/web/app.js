/* 所有业务文本通过 textContent 渲染；会话使用 HttpOnly Cookie，脚本不保存密码或设备密钥。 */
'use strict';
const app=document.querySelector('#app');
let me=null,csrf='',selected='',tab='records',page=0,view=null,users=[];
const emptyFilters=()=>({ledgerId:'',q:'',from:'',to:''});
let filters=emptyFilters();
let viewGeneration=0;
let sessionGeneration=0;
let waterDay='';
const tabs={records:'账本记录',water:'饮水记录',tags:'标签',devices:'设备',users:'用户管理',audit:'操作日志',settings:'账户设置',conflicts:'冲突历史'};
function el(tag,text='',cls=''){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n}
/** 只展示操作结果，不将密码、密钥或请求正文写入日志。 */
function notify(text){const n=document.querySelector('#notice');n.textContent=text;n.style.display='block';clearTimeout(n.timer);n.timer=setTimeout(()=>n.style.display='none',5000)}
class StaleSessionError extends Error {}
function requireSession(generation){if(generation!==sessionGeneration)throw new StaleSessionError()}
function reportFailure(error){if(!(error instanceof StaleSessionError))notify(error.message)}
/** 按钮执行期间禁用重复点击，异常统一显示可读提示。 */
function button(text,fn,cls=''){
 const generation=sessionGeneration,b=el('button',text,cls);b.type='button';
 b.addEventListener('click',async()=>{
  if(generation!==sessionGeneration)return;
  b.disabled=true;
  try{await fn(generation)}catch(error){reportFailure(error)}finally{if(b.isConnected)b.disabled=false}
 });
 return b;
}
/** 以包裹式标签建立输入关联，兼容键盘和屏幕阅读器。 */
function field(parent,label,value='',type='text'){const wrap=el('label',label,'field'),input=el('input');input.type=type;input.value=value;wrap.append(input);parent.append(wrap);return input}
function optionSelect(items,value){const n=el('select');for(const [id,label]of items){const o=el('option',label);o.value=id;n.append(o)}n.value=value;return n}
/** 弹窗关闭时移除节点，避免继续保留一次性凭证的显示内容。 */
function dialog(title){const d=el('dialog'),body=el('div'),actions=el('div','','actions');d.append(el('h2',title),body,actions);document.body.append(d);const close=()=>{d.close();d.remove()};actions.append(button('取消',close));d.addEventListener('cancel',e=>{e.preventDefault();close()});d.showModal();return{d,body,actions,close}}
/** 在请求及解析完成时检查原会话，迟到的成功、失败和备份内容均不能进入新会话。 */
async function sessionRequest(path,options,read){
 const generation=sessionGeneration;
 let response,value;
 try{
  response=await fetch('./console/api/'+path,{credentials:'same-origin',...options});
  requireSession(generation);
  value=response.ok?await read(response):await response.json();
 }catch(error){requireSession(generation);throw error}
 requireSession(generation);
 if(!response.ok){
  const message=value.error||'请求未完成';
  if(response.status===401&&me){renderLogin();notify(message);throw new StaleSessionError()}
  throw Error(message);
 }
 return value;
}
/** 写请求使用本会话的 CSRF 信息，二进制导出也受相同的会话检查。 */
function api(path,body){
 const options={headers:{Accept:'application/json'}};
 if(body!==undefined){options.method='POST';options.headers['Content-Type']='application/json';options.headers['X-CSRF-Token']=csrf;options.body=JSON.stringify(body)}
 return sessionRequest(path,options,response=>response.json());
}
/** 每次操作明确携带目标账户，服务端仍独立验证归属和角色。 */
function action(userId,command){return api('action',{...command,userId})}
/** 所有日期显式使用北京时间，与访问设备时区无关。 */
function time(ms){return ms==null?'进行中':new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',dateStyle:'short',timeStyle:'medium',hour12:false}).format(new Date(ms))}
function duration(ms){let s=Math.max(0,Math.floor(ms/1000));return `${String(Math.floor(s/3600)).padStart(2,'0')}:${String(Math.floor(s/60)%60).padStart(2,'0')}:${String(s%60).padStart(2,'0')}`}
function parts(record){return record.segments||[{kind:'focus',started:record.started,ended:record.ended}]}
/** 专注区间求和，避免把分心时间和汇总行重复计入。 */
function effective(record){return parts(record).filter(x=>x.kind==='focus').reduce((sum,x)=>sum+Math.max(0,(x.ended??Date.now())-x.started),0)}
function localTime(ms){return new Date(ms+8*3600000).toISOString().slice(0,23)}
function parseTime(input){if(!input.value)throw Error('请填写完整的北京时间');const ms=Date.parse(input.value+'+08:00');if(!Number.isFinite(ms))throw Error('时间格式无效');return ms}
function tagChoice(parent,value,tags){const label=el('label','标签','field');const values=[['','无标签'],...tags.map(x=>[x.name,x.name])];if(value&&!values.some(x=>x[0]===value))values.push([value,value+'（已删除）']);const select=optionSelect(values,value||'');label.append(select);parent.append(label);return select}
function setTag(record,tag){if(tag)record.tag=tag;else delete record.tag}

/* 账户首次登录被限定在改密页面，服务端也独立执行同一限制。 */
function useSession(result){sessionGeneration++;me=result.user;csrf=result.csrf;selected=me.id}
/** 登录表单只在内存读取密码，成功后立即清空输入。 */
function renderLogin(){
 sessionGeneration++;viewGeneration++;me=null;csrf='';selected='';tab='records';page=0;view=null;users=[];waterDay='';filters=emptyFilters();
 document.querySelectorAll('dialog').forEach(d=>d.remove());app.replaceChildren();
 const notice=document.querySelector('#notice');clearTimeout(notice.timer);notice.textContent='';notice.style.display='none';
 const box=el('form','','login');box.append(el('div','柳时','brand'),el('p','管理后台','muted'));
 const username=field(box,'用户名'),password=field(box,'密码','','password');username.autocomplete='username';password.autocomplete='current-password';
 const submit=el('button','登录','primary');submit.type='submit';box.append(submit);
 box.addEventListener('submit',async event=>{
  event.preventDefault();submit.disabled=true;
  const generation=sessionGeneration;
  try{
   const result=await api('login',{username:username.value,password:password.value});
   requireSession(generation);
   password.value='';notice.style.display='none';useSession(result);await load();
  }catch(error){reportFailure(error)}finally{submit.disabled=false}
 });
 app.append(box);
}
function passwordForm(parent,forced=false){
 const generation=sessionGeneration,userId=me.id;
 parent.append(el('h2',forced?'设置新密码':'修改本人密码'),el('p','密码至少 12 个字符，且不超过 256 字节。修改后需重新登录。','muted'));
 const form=el('form'),old=field(form,'原密码','','password'),next=field(form,'新密码','','password'),repeat=field(form,'确认新密码','','password');
 old.autocomplete='current-password';next.autocomplete=repeat.autocomplete='new-password';
 const submit=el('button','保存新密码','primary');submit.type='submit';form.append(submit);
 form.addEventListener('submit',async event=>{
  event.preventDefault();submit.disabled=true;
  try{
   requireSession(generation);
   if(next.value!==repeat.value)throw Error('两次新密码不一致');
   await action(userId,{action:'password',oldPassword:old.value,password:next.value});
   requireSession(generation);
   old.value=next.value=repeat.value='';renderLogin();notify('密码已更新，请重新登录');
  }catch(error){reportFailure(error)}finally{submit.disabled=false}
 });
 parent.append(form);
}
/** 先取得目标账户数据，再同时提交选中账户和页面；加载失败时保留完整的原视图。 */
async function load(overrides={}){
 if(!me)return renderLogin();
 const actorId=me.id,session=sessionGeneration,generation=++viewGeneration;
 const current=()=>generation===viewGeneration&&session===sessionGeneration;
 if(me.mustChangePassword){app.replaceChildren();const box=el('section','','login');box.append(el('div','柳时','brand'));passwordForm(box,true);app.append(box);return}
 const next={selected,tab,page,filters,waterDay,...overrides};
 let nextUsers=[],nextView=null;
 if(me.role==='admin'){
  nextUsers=(await api('users')).users;
  if(!current())return;
  if(!nextUsers.some(x=>x.user.id===next.selected))Object.assign(next,{selected:actorId,page:0,filters:emptyFilters(),waterDay:''});
 }else next.selected=actorId;
 if(next.tab==='water'){
  nextView=await api('water?'+new URLSearchParams({userId:next.selected,day:next.waterDay,page:next.page}));
 }else if(!['users','audit','settings'].includes(next.tab)){
  const kind=next.tab==='tags'?'tag':next.tab==='conflicts'?'conflict':'record';
  nextView=await api('state?'+new URLSearchParams({userId:next.selected,kind,page:next.page,...next.filters}));
 }
 if(!current())return;
 ({selected,tab,page,filters,waterDay}=next);
 users=nextUsers;view=nextView;
 renderShell();const content=document.querySelector('.content');if(tab==='records')renderRecords(content);else if(tab==='water')renderWater(content);else if(tab==='tags')renderTags(content);else if(tab==='devices')renderDevices(content);else if(tab==='users')renderUsers(content);else if(tab==='audit')await renderAudit(content);else if(tab==='conflicts')renderConflicts(content);else renderSettings(content)
}
function openAccount(userId,nextTab=tab){return load({selected:userId,tab:nextTab,page:0,filters:emptyFilters(),waterDay:''})}
/** 管理员可切换查看目标账户，普通用户仅显示自己的空间。 */
function renderShell(){
 app.replaceChildren();
 const header=el('header'),account=el('div','','account');
 header.append(el('div','柳时','brand'),el('span','管理后台','muted'),el('div','','spacer'));
 if(me.role==='admin'){
  account.append(el('span','查看账户','muted'));
  const select=optionSelect(users.map(x=>[x.user.id,x.user.displayName+' · '+x.user.username+(x.user.enabled?'':'（已停用）')]),selected);
  select.addEventListener('change',async()=>{
   const requested=select.value;
   // 选择框始终显示当前内容所属账户，待新账户页面可用后再显示新选择。
   select.value=selected;select.disabled=true;
   try{await openAccount(requested)}catch(e){reportFailure(e)}finally{select.disabled=false}
  });
  account.append(select);
 }else account.append(el('span',me.displayName));
 account.append(button('退出',async()=>{
  const generation=sessionGeneration;
  await action(me.id,{action:'logout'});
  requireSession(generation);renderLogin();
 }));
 header.append(account);
 const layout=el('div','','layout'),nav=el('nav');
 for(const [id,name]of Object.entries(tabs)){
  if((id==='users'||id==='audit')&&me.role!=='admin')continue;
  nav.append(button(name,()=>load({tab:id,page:0}),id===tab?'selected':''));
 }
 const content=el('section','','content');layout.append(nav,content);app.append(header,layout);
}
/** 宽表允许横向滚动，业务文字使用文本节点展示。 */
function table(parent,headers){const wrap=el('div','','panel table-scroll'),t=el('table'),head=el('tr');if(headers.length>=4)t.className='wide';wrap.tabIndex=0;wrap.setAttribute('aria-label','数据表格，可横向滚动');headers.forEach(x=>head.append(el('th',x)));const thead=el('thead');thead.append(head);const body=el('tbody');t.append(thead,body);wrap.append(t);parent.append(wrap);return body}
function row(body,values){const tr=el('tr');for(const value of values){const td=el('td');if(value instanceof Node)td.append(value);else td.textContent=value??'';tr.append(td)}body.append(tr);return tr}
function pager(parent,total){const bar=el('div','','pagination');const prev=button('上一页',()=>load({page:page-1})),next=button('下一页',()=>load({page:page+1}));prev.disabled=page===0;next.disabled=(page+1)*50>=total;bar.append(prev,el('span',`第 ${page+1} 页 · 共 ${total} 项`,'muted'),next);parent.append(bar)}
function controls(...buttons){const n=el('div','','toolbar');n.append(...buttons);return n}
/** 关闭或退出后，保存结果不再打开新弹窗，也不刷新其他会话的页面。 */
function namedDialog(title,label,save){
 const generation=sessionGeneration,d=dialog(title),input=field(d.body,label);
 const stillOpen=()=>d.d.isConnected&&generation===sessionGeneration;
 d.actions.append(button('保存',async()=>{
  if(!stillOpen())return;
  if(!input.value.trim())throw Error('内容不能为空');
  await save(input.value.trim(),stillOpen);
  if(stillOpen()){await load();d.close()}
 },'primary'));
 input.focus();
}
async function saveNamed(userId,kind,entity){const d=dialog(kind==='ledger'?'账本改名':'修改标签');const input=field(d.body,'名称',entity.name);d.actions.append(button('保存',async generation=>{await action(userId,{action:'save',kind,entity:{...entity,name:input.value},expectedVersion:entity.version});requireSession(generation);await load();d.close()},'primary'))}
async function deleteEntity(userId,kind,entity){const generation=sessionGeneration,name=kind==='record'?entity.task:entity.name;let confirmation=name;if(kind==='ledger'){confirmation=prompt(`删除账本将同时删除全部任务及分心区间。请输入账本名称“${name}”：`);if(confirmation===null)return}else if(!confirm(`确认删除${kind==='record'?'整个任务及其全部区间':'标签'}“${name}”？`))return;await action(userId,{action:'delete',kind,id:entity.id,expectedVersion:entity.version,confirmation});requireSession(generation);await load()}

/* 编辑专注元数据与各区间时间，提交前只改本地副本；服务端再次校验版本和完整时间线。 */
/** 编辑完整任务副本，专注元数据统一，分心与时间区间分别保存。 */
function recordDialog(current,ledgerId,record=null,segmentsOnly=false){const data=record?structuredClone(record):{id:crypto.randomUUID(),ledgerId:ledgerId||current.ledgers[0]?.id,task:'',started:Date.now()-60000,ended:Date.now(),deleted:false,revision:1};if(!data.ledgerId){notify('请先新建账本');return}const d=dialog(segmentsOnly?'分段明细':'任务记录');if(record?.ended===null){d.body.append(el('p','进行中的任务只读，请在客户端结束后修改。','muted'));for(const p of parts(record))d.body.append(el('p',`${p.kind==='focus'?'专注 · '+record.task:'分心 · '+p.task}　${time(p.started)} → ${time(p.ended)}`));return}
 const name=field(d.body,'原任务事项',data.task);const tag=tagChoice(d.body,data.tag,current.tags);const section=el('div');d.body.append(section);const inputs=[];
 for(const [i,p]of parts(data).entries()){const box=el('div','','segment');box.append(el('h3',`${i+1} · ${p.kind==='focus'?'专注':'分心'}`));const entry={part:p};if(p.kind==='distraction'){entry.task=field(box,'分心事项',p.task);entry.tag=tagChoice(box,p.tag,current.tags)}const grid=el('div','','grid');entry.start=field(grid,'开始（北京时间）',localTime(p.started),'datetime-local');entry.end=field(grid,'结束（北京时间）',localTime(p.ended),'datetime-local');entry.start.step=entry.end.step='0.001';box.append(grid);section.append(box);inputs.push(entry)}
 d.actions.append(button('保存',async generation=>{data.task=name.value.trim();setTag(data,tag.value);if(!data.task)throw Error('事项不能为空');const changed=inputs.map(x=>{const p={...x.part,started:parseTime(x.start),ended:parseTime(x.end)};if(p.kind==='distraction'){p.task=x.task.value.trim();setTag(p,x.tag.value)}return p});if(data.segments)data.segments=changed;data.started=changed[0].started;data.ended=changed.at(-1).ended;await action(current.user.id,{action:'save',kind:'record',entity:data,expectedVersion:record?.version||''});requireSession(generation);await load();d.close()},'primary'))
}
/** 先检查认证和导出结果，再生成本机下载，不把错误 JSON 当作备份。 */
async function download(userId,format,ledgerId){
 const generation=sessionGeneration,query=new URLSearchParams({userId,format,ledgerId});
 const content=await sessionRequest('export?'+query,{},response=>response.blob());
 requireSession(generation);
 const url=URL.createObjectURL(content),a=el('a');a.href=url;a.download=format==='csv'?'柳时时间记录.csv':'柳时备份.json';a.click();
 setTimeout(()=>URL.revokeObjectURL(url),1000);
}
/** 文件读取可能跨越页面切换，确认提示和上传请求须使用同一目标账户。 */
function importBackup(user){
 const generation=sessionGeneration,input=el('input');input.type='file';input.accept='.json,application/json';
 input.addEventListener('change',async()=>{
  try{
   const file=input.files[0];if(!file)return;
   if(file.size>8*1024*1024)throw Error('网页导入上限为 8 MiB；较大备份请使用客户端导入');
   const text=await file.text();
   requireSession(generation);
   const backup=JSON.parse(text);
   if(selected!==user.id)throw Error('查看账户已切换，请重新选择备份文件');
   if(!confirm(`将合并导入“${user.displayName}”的账户。重复数据去重，不同版本另存为冲突。继续？`))return;
   const result=await action(user.id,{action:'import',backup});
   requireSession(generation);
   notify(`导入完成：新增 ${result.added}，重复 ${result.duplicate}，冲突 ${result.conflicts}`);
   await load();
  }catch(error){if(generation===sessionGeneration)reportFailure(error)}
 });
 input.click();
}
/** 筛选、补记和备份入口均指向已授权账户，不影响客户端计时。 */
function renderRecords(parent){
 const current=view,{id:userId}=current.user,ledgerId=filters.ledgerId;
 parent.append(el('h1','账本记录'),el('p','起止时间固定为北京时间；有效时长仅累计专注区间。','muted'));
 const bar=el('div','','toolbar');
 const ledger=optionSelect([['','全部账本'],...current.ledgers.map(x=>[x.id,x.name])],ledgerId),search=el('input');
 search.placeholder='搜索事项或标签';search.value=filters.q;
 const from=el('input'),to=el('input');from.type=to.type='date';from.value=filters.from;to.value=filters.to;
 bar.append(ledger,search,from,to,button('筛选',()=>load({filters:{ledgerId:ledger.value,q:search.value,from:from.value,to:to.value},page:0})));parent.append(bar);
 parent.append(controls(
  button('新建账本',()=>namedDialog('新建账本','账本名称',name=>action(userId,{action:'save',kind:'ledger',entity:{id:crypto.randomUUID(),name,deleted:false,revision:1}})),'primary'),
  button('补记',()=>recordDialog(current,ledgerId)),button('导出 JSON',()=>download(userId,'json',ledgerId)),
  button('导出 CSV',()=>download(userId,'csv',ledgerId)),button('导入 JSON',()=>importBackup(current.user))
 ));
 const book=current.ledgers.find(x=>x.id===ledgerId);
 if(book)parent.append(controls(button('账本改名',()=>saveNamed(userId,'ledger',book)),button('删除账本',()=>deleteEntity(userId,'ledger',book),'danger')));
 if(current.hasActive)parent.append(el('p','该账户有任务正在计时，导入导出须在计时结束后进行。','muted'));
 const body=table(parent,['事项 / 账本','标签','起止时间','有效时长','操作']);
 for(const record of current.items){
  const buttons=controls(button(record.ended===null?'查看状态':'修改',()=>recordDialog(current,ledgerId,record)),button('分段明细',()=>recordDialog(current,ledgerId,record,true)));
  if(record.ended!==null)buttons.append(button('删除',()=>deleteEntity(userId,'record',record),'danger'));
  const bookName=current.ledgers.find(x=>x.id===record.ledgerId)?.name||'已删除账本';
  row(body,[record.task+'\n'+bookName,record.tag||'无标签',time(record.started)+'\n'+time(record.ended),duration(effective(record)),buttons]);
 }
 if(!current.items.length)parent.append(el('p','暂无记录','empty'));
 pager(parent,current.total);
}
/** 保存时绑定打开表单的目标账户及历史版本，防止重新载入后误操作其他账户。 */
function editWater(userId,entry){
 const d=dialog(entry?'更正饮水':'补记饮水');
 const amount=field(d.body,'饮水量（mL）',entry?.ml??250,'number');
 amount.min='1';amount.max='10000';amount.step='1';
 const at=field(d.body,'时间（北京时间）',localTime(entry?.at??Date.now()),'datetime-local');
 at.step='0.001';
 const id=crypto.randomUUID();
 d.actions.append(button('保存',async generation=>{
  const ml=amount.valueAsNumber;
  if(!Number.isInteger(ml)||!amount.checkValidity())throw Error('请输入 1 至 10000 mL 的整数');
  const water={id,kind:entry?'correct':'manual',at:parseTime(at),ml};
  if(entry){water.target=entry.id;water.base=entry.version}
  await api('water',{action:'water',userId,water});requireSession(generation);d.close();await load();
 },'primary'));
}

/** 网页管理补记、更正与作废。物理水位仅作为同步状态展示。 */
function renderWater(parent){
 const current=view,userId=current.user.id;
 parent.append(el('h1','饮水记录'),el('p',`${current.day} · 饮水 ${current.drank} mL`));
 const b=current.bottle;
 if(b?.capacity)parent.append(el('p',`水壶剩余 ${b.ml} / ${b.capacity} mL`,'muted'));
 const filters=el('div','','toolbar'),day=field(filters,'日期（北京时间）',current.day,'date');
 day.addEventListener('change',()=>load({waterDay:day.value,page:0}).catch(reportFailure));
 filters.append(button('今天',()=>load({waterDay:'',page:0})),button('补记饮水',()=>editWater(userId),'primary'));
 parent.append(filters);
 const names={fill:'加水',drink:'喝水',pour:'倒水',manual:'补记'};
 const body=table(parent,['时间（北京时间）','类型','水量','状态','操作']);
 for(const entry of current.items){
  const actions=controls();
  if(!entry.voided&&(entry.kind==='drink'||entry.kind==='manual')){
   actions.append(button('更正',()=>editWater(userId,entry)),button('作废',async generation=>{
    if(!confirm('作废后该条记录退出当日饮水统计。确认作废？'))return;
    await api('water',{action:'water',userId,water:{id:crypto.randomUUID(),kind:'void',at:Date.now(),target:entry.id,base:entry.version}});requireSession(generation);
    await load();
   },'danger'));
  }
  row(body,[time(entry.at),names[entry.kind],`${entry.ml} mL`,entry.voided?'已作废':'有效',actions]);
 }
 if(!current.items.length)parent.append(el('p','当天暂无记录','empty'));
 pager(parent,current.total);
}
function renderTags(parent){const userId=view.user.id;parent.append(el('h1','标签'),el('p','标签由本账户各账本共用。删除候选项保留任务中的历史名称。','muted'));parent.append(controls(button('新增标签',()=>namedDialog('新增标签','标签名称',name=>action(userId,{action:'save',kind:'tag',entity:{name,deleted:false,revision:1}})),'primary')));const body=table(parent,['标签名称','操作']);for(const tag of view.items)row(body,[tag.name,controls(button('删除',()=>deleteEntity(userId,'tag',tag),'danger'))]);pager(parent,view.total)}
function secretDialog(title,secret){const d=dialog(title);d.body.append(el('p','以下内容仅本次显示，请妥善保存。关闭后不能再次查看原文。','muted'),el('pre',secret,'secret'));d.actions.replaceChildren(button('复制',async()=>{await navigator.clipboard.writeText(secret);notify('已复制')}),button('已保存，关闭',d.close,'primary'))}
/** 一次性显示生成或轮换后的设备密钥，撤销需要明确确认。 */
function renderDevices(parent){
 const userId=view.user.id;
 parent.append(el('h1','设备'),el('p','每台设备使用独立密钥，填写到客户端设置后启用同步。最多十台有效设备。','muted'));
 parent.append(controls(button('添加设备',()=>namedDialog('添加设备','设备名称',async (label,stillOpen)=>{
  const r=await action(userId,{action:'create_device',label});
  if(stillOpen())secretDialog('设备密钥',r.key);
 }),'primary')));
 const body=table(parent,['设备名称','状态','最后同步（北京时间）','操作']);
 for(const device of view.devices){
  const actions=controls();
  if(!device.revoked)actions.append(button('重置密钥',async()=>{
   if(!confirm('重置后旧密钥立即失效，是否继续？'))return;
   const generation=sessionGeneration,r=await action(userId,{action:'rotate_device',id:device.id});
   requireSession(generation);secretDialog('新设备密钥',r.key);await load();
  }),button('撤销',async generation=>{
   if(!confirm(`撤销设备“${device.label}”的同步权限？`))return;
   await action(userId,{action:'revoke_device',id:device.id});requireSession(generation);await load();
  },'danger'));
  row(body,[device.label,device.revoked?'已撤销':'有效',device.lastSync?time(device.lastSync):'尚未同步',actions]);
 }
}
/** 普通用户创建、停用、重置密码与彻底删除集中由管理员执行。 */
function renderUsers(parent){
 const actorId=me.id;
 parent.append(el('h1','用户管理'),el('p','管理员可查看和管理所有账户。停用会使会话及设备权限失效；重新启用后需新建设备密钥。','muted'));
 parent.append(controls(button('创建用户',()=>{
  const d=dialog('创建普通用户'),username=field(d.body,'用户名（3—32 位字母、数字或 . _ -）'),name=field(d.body,'显示名称');
  d.actions.append(button('创建',async()=>{
   const generation=sessionGeneration,r=await action(actorId,{action:'create_user',username:username.value,displayName:name.value});
   requireSession(generation);
   if(!d.d.isConnected)return;
   d.close();secretDialog('临时密码 · '+r.user.username,r.temporaryPassword);await load();
  },'primary'));
 },'primary')));
 const body=table(parent,['账户','使用情况','最后同步','操作']);
 for(const item of users){
  const u=item.user,actions=controls(button('查看数据',()=>openAccount(u.id,'records')),button('修改名称',()=>{
   const d=dialog('修改账户显示名称'),name=field(d.body,'显示名称',u.displayName);
   d.actions.append(button('保存',async generation=>{await action(u.id,{action:'profile',displayName:name.value});requireSession(generation);await load();d.close()},'primary'));
  }));
  if(u.role!=='admin')actions.append(button('重置密码',async()=>{
   if(!confirm(`重置“${u.username}”的密码并使网页会话失效？`))return;
   const generation=sessionGeneration,r=await action(u.id,{action:'reset_password'});
   requireSession(generation);secretDialog('临时密码 · '+u.username,r.temporaryPassword);
  }),button(u.enabled?'停用':'启用',async generation=>{
   if(!confirm(`确认${u.enabled?'停用':'启用'}“${u.username}”？`))return;
   await action(u.id,{action:'user_status',enabled:!u.enabled});requireSession(generation);await load();
  }),button('彻底删除',async generation=>{
   const confirmation=prompt(`永久删除“${u.username}”的服务器业务数据及同步历史。离线副本和既有备份不随此操作清除。请输入用户名：`);
   if(confirmation===null)return;
   await action(u.id,{action:'delete_user',confirmation});requireSession(generation);await load();
  },'danger'));
  row(body,[u.displayName+'\n'+u.username+' · '+(u.role==='admin'?'管理员':u.enabled?'普通用户':'已停用'),`${item.ledgerCount} 个账本 / ${item.recordCount} 个任务 / ${item.deviceCount} 台设备${item.hasActive?'\n正在计时':''}`,item.lastSync?time(item.lastSync):'尚未同步',actions]);
 }
}
/** 分页展示审计元信息，保留期由服务端执行。 */
async function renderAudit(parent){parent.append(el('h1','操作日志'),el('p','记录近 180 天的账户、设备及业务变更，不包含密码和设备密钥原文。','muted'));const data=await api('audit?page='+page),body=table(parent,['时间','操作人','目标账户','操作','对象']);const names={login:'登录',login_failed:'登录失败',create_user:'创建用户',delete_user:'删除用户',user_status:'修改账户状态',reset_password:'重置密码',change_password:'修改密码',create_device:'创建设备',rotate_device:'重置设备密钥',revoke_device:'撤销设备',save_record:'保存任务',save_ledger:'保存账本',save_tag:'保存标签',delete_record:'删除任务',delete_ledger:'删除账本',delete_tag:'删除标签',import_backup:'导入备份',water_manual:'补记饮水',water_correct:'更正饮水',water_void:'作废饮水',profile:'修改账户名称'};for(const item of data.items)row(body,[time(item.at),item.actor||'未登录',item.target,names[item.action]||item.action,item.objectId]);const bar=controls(button('上一页',async()=>{page=Math.max(0,page-1);await load()}),button('下一页',async()=>{if(data.items.length===50){page++;await load()}}));parent.append(bar)}
/** 只读展示完整冲突分段，不提供重启计时或强制覆盖入口。 */
function renderConflicts(parent){parent.append(el('h1','冲突历史'),el('p','以下为保留的不同版本，查看不会恢复计时，也不会覆盖当前任务。','muted'));for(const content of view.items){const entity=content.entity,box=el('div','','panel conflict');if(content.kind==='record'){box.append(el('h3',entity.task),el('p',`标签：${entity.tag||'无标签'}　${entity.deleted?'删除状态':''}`));for(const p of parts(entity))box.append(el('p',`${p.kind==='focus'?'专注 · '+entity.task:'分心 · '+p.task}\n${time(p.started)} → ${time(p.ended)}${p.ended===null?'（存档）':''}`))}else box.append(el('h3',entity.name),el('p',entity.deleted?'删除状态':'历史版本'));parent.append(box)}if(!view.items.length)parent.append(el('p','暂无冲突历史','empty'));pager(parent,view.total)}
/** 本人密码修改不受管理员当前查看账户选择的影响。 */
function renderSettings(parent){
 const generation=sessionGeneration,userId=me.id;
 parent.append(el('h1','账户设置'));
 const panel=el('section','','panel');panel.append(el('p','用户名：'+me.username,'muted'));
 const name=field(panel,'本人显示名称',me.displayName);
 panel.append(button('保存名称',async()=>{
  await action(userId,{action:'profile',displayName:name.value});requireSession(generation);
  const result=await api('me');requireSession(generation);
  me=result.user;await load();
 },'primary'));
 parent.append(panel);
 const password=el('section','','panel');passwordForm(password);parent.append(password);
}
// 页面轮询仅在前台且无编辑弹窗时执行；读秒及表单输入不会逐字或逐帧发送到服务器。
setInterval(()=>{if(me&&!me.mustChangePassword&&!document.hidden&&!document.querySelector('dialog')&&tab==='records')load().catch(reportFailure)},15000);
(async()=>{try{const r=await api('me');useSession(r);await load()}catch(error){if(!(error instanceof StaleSessionError))renderLogin()}})();
