package dev.lyushi.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.Executors

/**
 * 安卓界面使用系统控件和文件选择器，不申请所有文件权限，也不每秒启动后台服务。
 * 正式应用与原型包隔离。文件读写在单独线程执行，UI 只显示已成功保存的结果。
 */
class MainActivity : Activity() {
    // 进程退出后不恢复未保存的表单；同一进程内旋转屏幕可使用实例状态恢复。
    companion object {
        private val draftSession = java.util.UUID.randomUUID().toString()
        // 仅供调试表单验收跨旋转比较，正式运行不会读取或设置这些值。
        private var editorTestStage = 0
        private var editorTestOriginal = JSONObject()
    }
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private lateinit var task: EditText
    private lateinit var clock: TextView
    private lateinit var elapsed: TextView
    private lateinit var action: Button
    private lateinit var ledgerSpinner: Spinner
    private lateinit var tagSpinner: Spinner
    private var selectedTag = ""
    private lateinit var status: TextView
    private lateinit var sync: SyncClient
    private val dialogs = mutableListOf<AlertDialog>()
    private var activeEditor: AlertDialog? = null
    private var activeDraft: ActiveMetadataDraft? = null
    private var activeDraftName: EditText? = null
    private var activeDraftTag: Spinner? = null
    private var activeDraftHint: TextView? = null
    private val locked = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // 表单界面验收使用独立偏好与数据库，禁止继承日常账户地址或设备密钥。
    private val activeEditorUiTest get() = BuildConfig.DEBUG && intent.getStringExtra("test_action") in listOf("active-editor-ui-test","active-editor-reopen-test")
    private val preferences by lazy { getSharedPreferences(if (activeEditorUiTest) "active-editor-ui-settings" else "settings", Context.MODE_PRIVATE) }
    private var loadedProfile = "local"
    private var accountReloadQueued = false
    private fun reloadAccount() { if(!accountReloadQueued && !isDestroyed) { accountReloadQueued=true;recreate() } }
    private val profileListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,name -> if(name=="accountProfile" && loadedProfile!=activeProfile(preferences)) reloadAccount() }
    private var selected = ""
    private var screen = "main"
    private var busy = false
    private var fileMode = 0
    private var actionRecordAtPress: String? = null
    private var actionVersionAtPress: String? = null
    private lateinit var focusHint: TextView
    private val refresh = object : Runnable { override fun run() { if (screen == "main") safely { render() }; if (!busy) sync.request(); handler.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = preferences.getInt("theme", 0)
        val dark = theme == 2 || (theme == 0 && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        // Android 13 及以后的系统返回通过新接口接管，保持详情页先返回主页面。
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (!busy) { if (screen != "main") showMain() else finish() }
        }
        if (activeEditorUiTest && savedInstanceState == null && intent.getStringExtra("test_action") == "active-editor-ui-test") {
            deleteDatabase("active-editor-ui-check.sqlite"); editorTestStage = 0
        }
        loadedProfile=activeProfile(preferences); store = Store(this, if (activeEditorUiTest) "active-editor-ui-check.sqlite" else accountDatabase(loadedProfile)); preferences.registerOnSharedPreferenceChangeListener(profileListener); selected = preferences.getString("ledger."+activeProfile(preferences), "") ?: ""; fileMode = savedInstanceState?.getInt("fileMode") ?: 0
        sync = SyncClient(applicationContext, store, preferences, worker, handler) { changed, reconnected ->
            if (!isDestroyed) safely {
                if (changed && !busy) {
                    applySynchronizedChange()
                }
                else if (screen == "main") render()
                if (reconnected) Toast.makeText(this, "已重新连接服务器，双方内容均已保留；冲突可在结束记录后查看。", Toast.LENGTH_LONG).show()
            }
        }
        showMain()
        // 恢复旋转前输入时保留原版本，不能把旧草稿无条件套用到远端最新任务。
        if (savedInstanceState?.getString("activeDraftSession") == draftSession) {
            savedInstanceState.getString("activeMetadataDraft")?.let { json -> safely {
                val draft = ActiveMetadataDraft.restore(json)
                if (draft.profile == loadedProfile) editActive(draft)
            } }
        }
        // 调试入口只使用另一份私有测试数据库，不清除或填充用户的正式记录。
        if (BuildConfig.DEBUG && intent.hasExtra("test_action")) safely { debugTest(intent.getStringExtra("test_action")!!) }
    }
    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("fileMode", fileMode)
        captureActiveDraft()?.let { outState.putString("activeMetadataDraft", it.json()); outState.putString("activeDraftSession", draftSession) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(profileListener)
        sync.close(); dialogs.toList().forEach { it.dismiss() }
        // 排在全部文件操作之后关闭数据库，避免后台任务与页面销毁竞争句柄。
        worker.execute { store.close() }; worker.shutdown(); super.onDestroy()
    }
    @Deprecated("系统旧版返回回调")
    override fun onBackPressed() { if (busy) return; if (screen != "main") showMain() else super.onBackPressed() }
    /** 统一处理用户可恢复错误，失败不会被显示为保存成功。 */
    private fun safely(block: () -> Unit) { try { block() } catch (e: Exception) { Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show() } }
    /** 跟踪对话框，远端开始计时时关闭管理表单，执行全设备已知的记录期间限制。 */
    private fun AlertDialog.present(onDismiss: () -> Unit = {}) { dialogs.add(this); setOnDismissListener { dialogs.remove(this); onDismiss() }; show() }
    private fun label(text: String, size: Float = 17f) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 12, 0, 12) }
    // 按钮可能包含用户事项，关闭系统默认大写转换，保持原文字形与大小写。
    private fun button(text: String, block: () -> Unit) = Button(this).apply { isAllCaps = false; this.text = text; setOnClickListener { safely(block) } }
    /** 系统栏的实际 inset 决定边距，兼容 Android 17 的全屏窗口布局。 */
    private fun page(title: String) {
        locked.clear(); root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(root) }; setContentView(scroll)
        val gap = (24 * resources.displayMetrics.density).toInt()
        scroll.setOnApplyWindowInsetsListener { _, insets -> val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); root.setPadding(gap + bars.left, gap + bars.top, gap + bars.right, gap + bars.bottom); insets }
        scroll.requestApplyInsets(); root.addView(label(title, 30f))
    }
    private fun idle() { check(!busy && store.active() == null) { "请先结束记录或等待文件处理完成" } }
    /** 同步后重建主页面并执行管理限制；活动编辑草稿独立保留并检查原前置版本。 */
    private fun applySynchronizedChange() {
        val draft = if (screen == "main" && task.isEnabled && store.active() == null) task.text.toString() else null
        if (store.active() != null) dialogs.toList().filter { it !== activeEditor }.forEach { it.dismiss() }
        showMain(); if (draft != null) task.setText(draft)
    }
    /** 同名账本的选择按编号保存，刷新页面不改变未结束记录所属账本。 */
    private fun showMain() {
        screen = "main"; page("柳时"); clock = label(""); root.addView(clock)
        val current = store.active(); if (current != null) selected = current.getString("ledgerId")
        val ledgers = store.entities("ledger")
        if (ledgers.none { it.getString("id") == selected }) selected = ledgers.firstOrNull()?.getString("id") ?: ""
        ledgerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, if (ledgers.isEmpty()) listOf("请先新建时间账本") else ledgers.map { it.getString("name") })
            if (ledgers.isNotEmpty()) setSelection(ledgers.indexOfFirst { it.getString("id") == selected })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (store.active() == null && position in ledgers.indices) { selected = ledgers[position].getString("id"); preferences.edit().putString("ledger."+activeProfile(preferences), selected).apply() } }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(ledgerSpinner); locked.add(ledgerSpinner)
        root.addView(button("新建账本") { idle(); promptName("新建账本", "") { selected = store.addLedger(it); showMain() } }.also { locked.add(it) })
        val taskRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        task = EditText(this).apply { hint = "现在正在做什么？"; maxLines = 4; setText(current?.let { segmentRecord(it, taskSegments(it).lastIndex).getString("task") } ?: "") }
        taskRow.addView(task, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (current != null) taskRow.addView(button("修改") { editActive() })
        root.addView(taskRow)
        // 新增及删除入口位于下拉框右侧；管理标签时保留尚未提交的事项输入。
        root.addView(label("标签（可选）", 14f))
        val tagRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tagSpinner = tagChoice(current?.let { segmentRecord(it, taskSegments(it).lastIndex).optString("tag") } ?: selectedTag, current != null)
        tagRow.addView(tagSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); locked.add(tagSpinner)
        tagRow.addView(button("删除") {
            idle(); val name = chosenTag(tagSpinner); check(name.isNotEmpty()) { "请先选择标签" }
            AlertDialog.Builder(this).setTitle("删除标签").setMessage("删除标签“$name”？历史任务保留原标签，删除会同步到其他设备。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> safely {
                    idle(); val draft = task.text.toString(); store.deleteTag(name); selectedTag = ""; showMain(); task.setText(draft); sync.request(true)
                } }.create().present()
        }.also { locked.add(it) })
        tagRow.addView(button("新增") {
            idle(); val draft = task.text.toString()
            promptName("新增标签（最多 200 字）", "") { selectedTag = store.addTag(it); showMain(); task.setText(draft); sync.request(true) }
        }.also { locked.add(it) }); root.addView(tagRow)
        elapsed = label("00:00:00", 42f).apply { gravity = android.view.Gravity.CENTER }; root.addView(elapsed)
        action = button("开始记录") {
            check(!busy) { "正在处理文件" }; val current = store.active()?.getString("id") ?: ""
            val intended = actionRecordAtPress ?: current; actionRecordAtPress = null
            val version = actionVersionAtPress ?: store.active()?.optString("version"); actionVersionAtPress = null
            check(version == store.active()?.optString("version")) { "计时状态已由其他设备改变，请核对后重试" }
            check(intended == current) { "计时状态已由其他设备改变，请核对页面后再操作" }
            if (store.active() == null) store.start(selected, task.text.toString(), chosenTag(tagSpinner)) else if (isDistracted(store.active())) store.resumeFocus(System.currentTimeMillis(), version!!) else { store.finish(expectedVersion = version); task.setText("") }; showMain(); sync.request(true)
        }
        // 返回 false，让系统保留正常点击与无障碍行为；这里只捕获用户按下时的操作对象。
        action.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) { actionRecordAtPress = store.active()?.getString("id") ?: ""; actionVersionAtPress = store.active()?.optString("version") }
            else if (event.action == android.view.MotionEvent.ACTION_CANCEL) { actionRecordAtPress = null; actionVersionAtPress = null }
            false
        }; root.addView(action)
        focusHint = label("", 14f); root.addView(focusHint)
        val distracted = isDistracted(current)
        if (current != null && !distracted) root.addView(button("分心一下") { startDistraction() })
        if (distracted) root.addView(button("结束全部") {
            val version = store.active()!!.getString("version")
            AlertDialog.Builder(this).setTitle("结束全部").setMessage("结束分心和本次任务？已记录的全部时间区间将保留。")
                .setNegativeButton("取消",null).setPositiveButton("结束") { _, _ -> safely { store.finish(expectedVersion = version); showMain(); sync.request(true) } }.create().present()
        })
        root.addView(button("账本明细与管理") { idle(); check(selected.isNotEmpty()) { "请先新建账本" }; showDetails() }.also { locked.add(it) })
        root.addView(button("从 JSON 备份恢复") { chooseFile(0) }.also { locked.add(it) })
        root.addView(button("设置") { check(!busy); showSettings() })
        root.addView(button("立即同步") { sync.request(true) })
        root.addView(button("查看保留的冲突版本") { showConflicts() }.also { locked.add(it) })
        status = label("", 13f); root.addView(status); render()
    }
    /** 保存旋转前的最新输入；捕获本身没有任何数据库副作用。 */
    private fun captureActiveDraft(): ActiveMetadataDraft? = activeDraft?.also {
        it.name = activeDraftName?.text?.toString() ?: it.name
        it.tag = activeDraftTag?.let { choice -> chosenTag(choice) } ?: it.tag
    }
    /** 远端更新只标记表单过期，保留文字和标签供用户核对、复制或取消。 */
    private fun checkActiveDraft() {
        val draft = activeDraft ?: return; val current = store.active()
        if (current?.optString("id") != draft.id || current.optString("version") != draft.version) {
            activeDraftHint?.text = "任务状态已更新，输入已保留。请核对主页面，取消后重新打开修改。"
            activeEditor?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        }
    }
    /**
     * 明确保存才更新当前阶段的名称和标签；编辑过程中计时不停止。
     * 实例状态仅保留草稿，取消及退出会丢弃；历史删除标签只保留原任务快照。
     */
    private fun editActive(restored: ActiveMetadataDraft? = null) {
        // dismiss 回调由消息队列投递；已关闭的旧窗口不能阻止立即再次打开。
        check(!busy); if (activeEditor?.isShowing == true) return
        val draft = restored ?: checkNotNull(store.active()) { "没有进行中的记录" }.let { current ->
            val visible = segmentRecord(current, taskSegments(current).lastIndex)
            ActiveMetadataDraft(loadedProfile, current.getString("id"), current.getString("version"), isDistracted(current), visible.optString("tag"), visible.getString("task"), visible.optString("tag"))
        }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,0,24,0) }
        val hint = label(if (draft.distracted) "仅修改当前分心，原任务和先前分心保持原样。计时继续。" else "名称与标签应用于本次任务的全部专注区间。计时继续。",14f)
        fields.addView(hint)
        val name = EditText(this).apply { setText(draft.name); maxLines = 4; contentDescription = "当前事项草稿" }; fields.addView(name)
        fields.addView(label("标签")); val choice = tagChoice(draft.tag, true, setOf(draft.originalTag), false); fields.addView(choice)
        val dialog = AlertDialog.Builder(this).setTitle(if (draft.distracted) "修改当前分心" else "修改当前事项")
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        activeDraft = draft; activeDraftName = name; activeDraftTag = choice; activeDraftHint = hint; activeEditor = dialog
        dialog.present {
            if (activeEditor === dialog) { activeEditor = null; activeDraft = null; activeDraftName = null; activeDraftTag = null; activeDraftHint = null }
        }
        // show 已创建按钮，立即替换默认关闭行为；不等待异步 OnShow，避免首帧点击漏保存。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely {
            check(!busy); captureActiveDraft()
            store.editActiveMetadata(draft.id, draft.version, draft.name, draft.tag)
            dialog.dismiss(); showMain(); sync.request(true)
        } }; checkActiveDraft()
    }
    /** 表单确认时才切换状态；取消不改变原任务，远端版本变化会阻止提交。 */
    private fun startDistraction() {
        val current = checkNotNull(store.active()); check(!isDistracted(current)) { "请先返回原任务" }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,0,24,0) }
        val name = EditText(this).apply { hint = "分心事项，留空记为分心" }; fields.addView(name)
        fields.addView(label("标签")); val choice = tagChoice(""); fields.addView(choice)
        val dialog = AlertDialog.Builder(this).setTitle("分心一下").setView(fields).setPositiveButton("开始分心",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely {
            check(!busy); store.distract(name.text.toString(), chosenTag(choice), System.currentTimeMillis(), current.getString("version")); dialog.dismiss(); showMain(); sync.request(true)
        } } }; dialog.present()
    }
    /** 主任务汇总独立于时间区间列表，修改后重新读取聚合版本，避免覆盖另一设备的新数据。 */
    private fun showSegments(record: JSONObject) {
        idle(); screen = "segments"; page("分段明细 · ${record.getString("task")}")
        taskSegments(record).forEachIndexed { index, segment ->
            val item = segmentRecord(record,index); val kind = if (segment.getString("kind") == "focus") "专注" else "分心"
            root.addView(button("$kind · ${item.getString("task")}\n${dateText(item.getLong("started"))} → ${dateText(item.getLong("ended"))}\n${durationText(item.getLong("ended")-item.getLong("started"))} · ${item.optString("tag", "无标签")}") { edit(record,index) })
        }
        root.addView(button("返回账本明细") { showDetails() })
    }
    /** 下拉项与显示文字分离，已删除快照不会被误认为可供新任务使用的标签。 */
    private fun tagChoice(initial: String, keepHistorical: Boolean = false, snapshots: Set<String> = emptySet(), updateMainSelection: Boolean = true): Spinner {
        val names = store.entities("tag").map { it.getString("name") }.distinct().sorted()
        val values = listOf("") + names + if (keepHistorical) (snapshots + initial).filter { it.isNotEmpty() && it !in names }.sorted() else emptyList()
        return Spinner(this).apply {
            tag = values
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values.map {
                if (it.isEmpty()) "无标签" else if (it !in names) "$it（已删除）" else it
            })
            setSelection(values.indexOf(initial).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (updateMainSelection && screen == "main" && store.active() == null) selectedTag = values[position] }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }
    /** 读取的是标签原始值，不将“无标签”占位文字写入数据库。 */
    @Suppress("UNCHECKED_CAST")
    private fun chosenTag(choice: Spinner): String = (choice.tag as List<String>)[choice.selectedItemPosition.coerceAtLeast(0)]
    /** 显示按时间差重算；后台暂停回调只影响刷新，不会丢失开始时刻。 */
    private fun render() {
        val now = System.currentTimeMillis(); clock.text = dateText(now) + " · 北京时间"
        val current = store.active(); val running = current != null
        locked.forEach { it.isEnabled = !running && !busy }; task.isEnabled = !running && !busy
        action.isEnabled = !busy && (running || selected.isNotEmpty()); action.text = if (isDistracted(current)) "结束分心并继续" else if (running) "结束记录" else "开始记录"
        val display = current?.let { segmentRecord(it, taskSegments(it).lastIndex) }
        if (display != null && task.text.toString() != display.getString("task")) task.setText(display.getString("task"))
        focusHint.text = if (isDistracted(current)) "分心中 · 待恢复：${current!!.getString("task")}" else if (running) "原任务累计有效时长" else ""
        elapsed.text = if (current == null) "00:00:00" else durationText(if (isDistracted(current)) now - display!!.getLong("started") else focusDuration(current, now))
        status.text = if (busy) "正在处理文件…" else if (current != null && now < display!!.getLong("started")) "设备时间早于开始时间，请核对系统时间"
            else sync.status + if (store.hasConflicts()) " · 已保留冲突版本" else ""
        checkActiveDraft()
    }
    /** 账本名输入框不会预先执行更改，取消或空白校验失败均保留原数据。 */
    private fun promptName(title: String, initial: String, save: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSingleLine(true) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely { idle(); save(input.text.toString()); dialog.dismiss() } } }; dialog.present()
    }
    /** 账本明细按开始时间倒序；每个条目的操作始终引用稳定编号。 */
    private fun showDetails() {
        idle(); screen = "details"; val ledger = store.entities("ledger").first { it.getString("id") == selected }; page(ledger.getString("name"))
        root.addView(label("起止时间均为北京时间", 13f))
        root.addView(button("补记") { idle(); edit(null) })
        val records = store.entities("record").filter { it.getString("ledgerId") == selected }.sortedByDescending { it.getLong("started") }
        if (records.isEmpty()) root.addView(label("这个账本还没有记录。"))
        records.forEach { record -> root.addView(button(record.getString("task") + "\n" + dateText(record.getLong("started")) + " → " + dateText(record.getLong("ended")) + "\n" + durationText(focusDuration(record, record.getLong("ended"))) + "（有效时长）\n标签：" + record.optString("tag", "无标签")) {
            idle(); AlertDialog.Builder(this).setTitle(record.getString("task")).setItems(arrayOf("修改事项与标签", "查看分段", "删除任务")) { _, which -> safely {
                if (which == 0) edit(record) else if (which == 1) showSegments(record) else AlertDialog.Builder(this).setTitle("删除记录").setMessage("确认删除整个任务及全部专注、分心区间？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> safely { idle(); store.deleteRecord(record.getString("id")); showDetails() } }.create().present()
            } }.create().present()
        }) }
        root.addView(button("账本改名") { promptName("账本改名", ledger.getString("name")) { store.renameLedger(selected, it); showDetails() } })
        root.addView(button("删除整个账本") { idle(); val input = EditText(this); val name = ledger.getString("name")
            AlertDialog.Builder(this).setTitle("删除整个账本").setMessage("将删除 ${records.size} 条记录。输入账本名“$name”确认：").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> safely { idle(); check(input.text.toString() == name) { "名称不一致，未删除" }; store.deleteLedger(selected); showMain() } }.create().present()
        })
        root.addView(button("备份全部 JSON") { chooseFile(1) }); root.addView(button("导出本账本 CSV") { chooseFile(2) }); root.addView(button("导出全部 CSV") { chooseFile(3) })
        root.addView(button("导入 JSON") { chooseFile(0) }); root.addView(button("查看已保留的导入冲突") { showConflicts() }); root.addView(button("返回") { showMain() })
    }
    /** 表单保留精确输入渠道，严格解析日期，避免把不存在的日期自动修正。 */
    private fun edit(record: JSONObject?, segment: Int = -1) {
        idle(); val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        val display = if (record == null || segment < 0) record else segmentRecord(record,segment)
        val taskInput = EditText(this).apply { hint = "事项"; setText(display?.getString("task") ?: "") }; fields.addView(taskInput)
        fields.addView(label("标签", 14f)); val recordTag = tagChoice(display?.optString("tag") ?: "", true); fields.addView(recordTag)
        fun timeInput(title: String, value: Long): EditText { fields.addView(label(title, 14f)); return EditText(this).apply { setSingleLine(true); setText(dateText(value, true)); fields.addView(this) } }
        val begin = timeInput("开始（北京时间）", display?.getLong("started") ?: (System.currentTimeMillis() - 60000))
        val end = timeInput("结束（北京时间）", display?.getLong("ended") ?: System.currentTimeMillis())
        if (segment < 0 && record?.has("segments") == true) { begin.isEnabled = false; end.isEnabled = false; fields.addView(label("起止时间请在分段明细修改；事项与标签应用到全部专注区间。",14f)) }
        val dialog = AlertDialog.Builder(this).setTitle(if (record == null) "补记" else "修改记录").setView(fields).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely {
            idle(); val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withResolverStyle(ResolverStyle.STRICT)
            fun parse(input: EditText) = LocalDateTime.parse(input.text.toString(), format).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
            if (segment >= 0) store.editSegment(record!!.getString("id"), segment, taskInput.text.toString(), chosenTag(recordTag), parse(begin), parse(end), record.getString("version"))
            else store.edit(record?.getString("id"), selected, taskInput.text.toString(), parse(begin), parse(end), record?.optString("version"), chosenTag(recordTag)); dialog.dismiss(); if (segment >= 0) showSegments(store.find("record", record!!.getString("id"))!!) else showDetails()
        } } }; dialog.present()
    }
    /** 冲突只读展示双方保留策略；完整导入版本也会随 JSON 备份导出。 */
    private fun showConflicts() {
        idle(); screen = "conflicts"; page("已保留的冲突版本")
        root.addView(label("联网或导入时发现的不同内容已保留。这里是存档，查看不会重新开始计时；JSON 备份也包含这些内容。", 14f))
        if (store.conflicts().isEmpty()) root.addView(label("没有导入冲突。"))
        val names = store.entities("ledger",true).associate { it.getString("id") to it.getString("name") }
        store.conflicts().forEachIndexed { index, content ->
            val entity = content.getJSONObject("entity")
            val description = buildString {
                append("版本 ${index+1}"); if (entity.getBoolean("deleted")) append(" · 删除状态，原内容仍保留"); append("\n")
                if (content.getString("kind") in listOf("ledger", "tag")) append("名称：${entity.getString("name")}")
                else { append(entity.getString("task")); append("\n标签：${entity.optString("tag", "无标签")}"); append("\n账本：${names[entity.getString("ledgerId")] ?: "已删除或未知账本"}")
                    append("\n开始：${dateText(entity.getLong("started"))}"); append(if (entity.isNull("ended")) "\n结束：此版本尚未结束（存档）" else "\n结束：${dateText(entity.getLong("ended"))}") }
            }
            val segments = if (!entity.has("segments")) "" else taskSegments(entity).mapIndexed { i, part ->
                val row = segmentRecord(entity,i)
                "\n${if (part.getString("kind") == "focus") "专注" else "分心"} · ${row.getString("task")} · ${dateText(row.getLong("started"))} → ${if (row.isNull("ended")) "尚未结束（存档）" else dateText(row.getLong("ended"))} · 标签：${row.optString("tag", "无标签")}" }.joinToString("")
            root.addView(label(description + segments, 16f).apply { setTextIsSelectable(true) })
        }
        root.addView(button("返回") { showMain() })
    }
    /** 密钥通过 Keystore 单独保存，不写普通设置、不带入备份；记录时锁定连接设置。 */
    private fun showSettings() {
        screen = "settings"; page("设置"); root.addView(label("语言：简体中文")); root.addView(label("版本 ${BuildConfig.VERSION_NAME}",14f))
        root.addView(label("主题",14f)); val theme = Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("跟随系统","白天","黑夜"));setSelection(preferences.getInt("theme",0)) };root.addView(theme)
        root.addView(button("保存外观") { check(!busy) { "正在处理账户连接" };check(preferences.edit().putInt("theme",theme.selectedItemPosition).commit()) { "设置保存失败" };recreate() })
        val current = activeProfile(preferences)
        val ids = listOf("local") + (preferences.getStringSet("accountProfiles",emptySet()) ?: emptySet()).sorted()
        root.addView(label("已保存账户",14f));val profiles=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,ids.map { if(it=="local") "单机数据" else preferences.getString("accounts.$it.name",it.take(12))!! });setSelection(ids.indexOf(current).coerceAtLeast(0));isEnabled=store.active()==null };root.addView(profiles)
        root.addView(button("切换至所选数据空间") { idle();check(preferences.edit().putString("accountProfile",ids[profiles.selectedItemPosition]).commit()) { "账户切换保存失败" };reloadAccount() }.apply { isEnabled=store.active()==null })
        val server=EditText(this).apply { hint="服务器地址 https://";setSingleLine(true);setText(accountAddress(preferences));isEnabled=store.active()==null };root.addView(server)
        val key=EditText(this).apply { hint="后台生成的设备密钥";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isEnabled=store.active()==null };root.addView(key)
        safely { key.setText(SecretVault(this,profileVault(current)).read()) }
        root.addView(label("连接前先验证账户；不同账户的数据与密钥独立保存。记录期间禁止切换账户。",14f))
        root.addView(button("验证并连接账户") {
            idle();val address=server.text.toString().trim().trimEnd('/');val secret=key.text.toString().trim();busy=true
            worker.execute {
                val result=runCatching { check(store.active()==null) { "请先结束当前记录" }; deviceIdentity(address,secret) }
                runOnUiThread { if(!isDestroyed) { busy=false;result.fold(onSuccess={identity ->
                    fun connect(merge: Boolean) {
                        idle();busy=true
                        worker.execute {
                            val saved=runCatching {
                                check(store.active()==null) { "记录状态发生变化，请稍后重试" }
                                val backup=if(merge) store.backup() else null
                                val profile=accountProfileId(address,identity)
                                Store(this,accountDatabase(profile)).use { target -> target.bindAccount(identity.getString("userId"),identity.getString("serverId"));if(backup!=null)target.importBackup(backup) }
                                SecretVault(this,profileVault(profile)).write(secret)
                                val known=(preferences.getStringSet("accountProfiles",emptySet())?:emptySet()).toMutableSet().apply { add(profile) }
                                check(preferences.edit().putString("accounts.$profile.server",address).putString("accounts.$profile.name",identity.getString("displayName")+" · "+identity.getString("username")).putStringSet("accountProfiles",known).putString("accountProfile",profile).commit()) { "账户设置保存失败" }
                            }
                            runOnUiThread { if(!isDestroyed) { busy=false;if(saved.isSuccess)reloadAccount() else Toast.makeText(this,saved.exceptionOrNull()?.message?:"连接未完成",Toast.LENGTH_LONG).show() } }
                        }
                    }
                    if(current=="local"&&store.entities("ledger",true).isNotEmpty()) AlertDialog.Builder(this).setTitle("首次连接账户").setMessage("连接账户“${identity.getString("displayName")}”。是否将当前单机数据合并到该账户？选择否将使用账户独立数据，单机数据仍保留。")
                        .setPositiveButton("合并") { _,_ -> safely { connect(true) } }.setNeutralButton("不合并") { _,_ -> safely { connect(false) } }.setNegativeButton("取消",null).create().present()
                    else safely { connect(false) }
                },onFailure={Toast.makeText(this,it.message?:"账户验证未完成",Toast.LENGTH_LONG).show()}) } }
            }
        }.apply { isEnabled=store.active()==null })
        root.addView(button("返回") { check(!busy) { "正在验证账户" };showMain() })
    }
    /** Android 文件选择器授予单文件访问权；取消选择不修改数据。 */
    private fun chooseFile(mode: Int) {
        idle(); fileMode = mode
        if (mode == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 100)
        else startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = if (mode == 1) "application/json" else "text/csv"; putExtra(Intent.EXTRA_TITLE, if (mode == 1) "柳时备份.json" else "时间记录.csv") }, 101)
    }
    /** 文件操作占用独立线程；完整校验失败时数据库回滚且返回可读错误。 */
    private fun fileWork(block: () -> String) {
        busy = true; showMain()
        worker.execute {
            val result = runCatching(block)
            runOnUiThread { if (!isDestroyed) { busy = false; showMain(); AlertDialog.Builder(this).setTitle(if (result.isSuccess) "操作完成" else "操作未完成").setMessage(result.getOrElse { it.message ?: "文件处理失败" }).setPositiveButton("知道了", null).create().present() } }
        }
    }
    @Deprecated("系统文件选择器的兼容回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        safely {
            idle()
            if (requestCode == 100) AlertDialog.Builder(this).setTitle("合并导入").setMessage("相同内容去重，不同版本另存为冲突，不覆盖本机。继续？").setNegativeButton("取消", null)
                .setPositiveButton("导入") { _, _ -> fileWork {
                    val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                        while (true) { val count = input.read(chunk); if (count < 0) break; check(output.size() + count <= 32 * 1024 * 1024) { "文件超过 32 MiB" }; output.write(chunk, 0, count) }; output.toByteArray()
                    }
                    val result = store.importBackup(JSONObject(bytes.toString(Charsets.UTF_8))); "新增 ${result.getInt("added")}，重复 ${result.getInt("duplicate")}，另存冲突 ${result.getInt("conflicts")}。"
                } }.create().present()
            else if (requestCode == 101) { val mode = fileMode; val ledger = selected; fileWork {
                val text = if (mode == 1) store.backup().toString(2) else store.csv(if (mode == 2) ledger else null)
                contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(text) }; "文件已保存。"
            } }
        }
    }
    /** 调试检查用独立 SQLite 文件，覆盖导入、隔离和跨进程恢复；不记录设备密钥。 */
    private fun debugTest(mode: String) {
        if (mode == "active-editor-ui-test" || mode == "active-editor-reopen-test") {
            // 真实 Activity 旋转及表单交互验收；偏好和数据库均已在入口隔离。
            val report = File(filesDir,"active-editor-ui-result.json")
            fun complete(block: () -> Unit) {
                val result = runCatching { block(); JSONObject().put("passed",true).put("phase",mode).put("record",store.active()) }
                report.writeText(result.getOrElse { JSONObject().put("passed",false).put("error",it.stackTraceToString()) }.toString(2),Charsets.UTF_8)
            }
            if (mode == "active-editor-reopen-test") {
                complete { check(activeEditor == null && activeDraft == null); check(store.active()!!.getString("task") == "已保存事项") }
            } else if (editorTestStage == 0) {
                report.delete(); val book=store.addLedger("表单旋转验收"); store.addTag("学习"); store.start(book,"原事项")
                editorTestOriginal=store.active()!!; showMain(); editActive()
                activeDraftName!!.setText("旋转前草稿"); val choice=activeDraftTag!!
                @Suppress("UNCHECKED_CAST") val names=choice.tag as List<String>
                choice.setSelection(names.indexOf("学习")); editorTestStage=1
                handler.postDelayed({ requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT },300)
            } else if (editorTestStage == 1) {
                handler.postDelayed({ complete {
                    check(activeEditor?.isShowing == true && activeDraftName!!.text.toString() == "旋转前草稿" && chosenTag(activeDraftTag!!) == "学习") { "旋转丢失草稿" }
                    check(activeDraft!!.version == editorTestOriginal.getString("version")) { "旋转重置前置版本" }
                    var current=store.active()!!; store.editActiveMetadata(current.getString("id"),current.getString("version"),"另一设备更正","")
                    applySynchronizedChange()
                    check(activeEditor?.isShowing == true && activeDraftName!!.text.toString() == "旋转前草稿" && !activeEditor!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled) { "远端更新未保留过期草稿" }
                    activeEditor!!.cancel(); editActive()
                    activeDraftName!!.setText("已保存事项"); activeEditor!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    current=store.active()!!; check(current.getString("task") == "已保存事项" && current.getLong("started") == editorTestOriginal.getLong("started") && current.getString("id") == editorTestOriginal.getString("id") && current.isNull("ended") && !current.has("segments"))
                    editActive(); activeDraftName!!.setText("取消草稿"); activeEditor!!.cancel()
                    check(store.active()!!.getString("task") == "已保存事项")
                    // 远端结束后仍保留输入和过期提示，取消不会重新启动任务。
                    editActive(); activeDraftName!!.setText("结束后草稿"); store.finish(current.getLong("started")+1000,current.getString("version")); applySynchronizedChange()
                    check(activeEditor?.isShowing == true && activeDraftName!!.text.toString() == "结束后草稿" && !activeEditor!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                    activeEditor!!.cancel(); check(store.active() == null)
                    // 另开一项并留下未保存草稿，外部强制退出后验证数据库仅保留已保存值。
                    store.start(current.getString("ledgerId"),"已保存事项"); showMain(); editActive(); activeDraftName!!.setText("退出时未保存草稿"); editorTestStage=2
                } },300)
            }
            return
        }
        if (mode == "sync-store-test") {
            val report = File(filesDir,"sync-store-result.json"); report.delete()
            // 大数据边界场景在工作线程运行，测试脚本以报告为准，不依赖界面启动等待时间。
            worker.execute {
                val result = runCatching { runSyncChecks(this) }.getOrElse { JSONObject().put("passed",false).put("error",it.message ?: "存储协议检查失败") }
                report.writeText(result.toString(2),Charsets.UTF_8)
            }
            return
        }
        // HTTPS 三端验收使用专用数据库和临时验收密钥；配置不经 Intent 参数或日志传递。
        if (mode == "sync-test") {
            val commandFile = File(filesDir, "sync-command.json")
            val command = JSONObject(commandFile.readText(Charsets.UTF_8)); commandFile.delete()
            val report = File(filesDir, "sync-result.json"); report.delete()
            worker.execute {
                val result = runCatching { Store(this, if(command.has("testId")) { val id=command.getString("testId");check(Regex("^[a-z0-9-]{1,64}$").matches(id));"network-$id.sqlite" } else "network-test.sqlite").use { isolated ->
                    when (command.getString("action")) {
                        "seed" -> isolated.start(isolated.addLedger("三端验收"), "跨进程恢复")
                        "distract" -> isolated.distract(command.optString("task","分心"),"",System.currentTimeMillis(),isolated.active()!!.getString("version"))
                        "resume-focus" -> isolated.resumeFocus(System.currentTimeMillis(),isolated.active()!!.getString("version"))
                        "finish" -> isolated.finish()
                        "edit" -> { val record = isolated.entities("record").first { !it.isNull("ended") && !it.getBoolean("deleted") }; isolated.edit(record.getString("id"), record.getString("ledgerId"), command.getString("task"), record.getLong("started"), record.getLong("ended")) }
                        "edit-active" -> isolated.editActiveMetadata(command.getString("id"),command.getString("expectedVersion"),command.getString("task"),command.optString("tag"))
                        "pull" -> Unit
                        else -> error("未知验收操作")
                    }
                    syncBlocking(isolated, command.getString("url"), command.getString("key"))
                    JSONObject().put("passed", true).put("records", org.json.JSONArray(isolated.entities("record", true)))
                        .put("ledgers", org.json.JSONArray(isolated.entities("ledger", true))).put("conflicts", org.json.JSONArray(isolated.conflicts())).put("pending", isolated.pendingCount())
                } }
                report.writeText(result.getOrElse { JSONObject().put("passed", false).put("error", it.message ?: "HTTPS 验收失败") }.toString(2), Charsets.UTF_8)
            }
            return
        }
        // 每次完整自检只重置专用测试库；跨进程恢复库与用户数据库都保持独立。
        if (mode == "self-test") { deleteDatabase("self-test.sqlite"); deleteFile("self-test.json") }
        Store(this, if (mode == "self-test" || mode == "export") "self-test.sqlite" else "test.sqlite").use { test ->
            when (mode) {
                "seed" -> test.start(test.addLedger("恢复检查"), "跨进程恢复")
                "resume" -> { check(test.active()?.getString("task") == "跨进程恢复"); test.finish() }
                "self-test" -> {
                    val one = test.addLedger("工作"); val two = test.addLedger("生活")
                    test.start(one, "中文，逗号\n第二行"); check(runCatching { test.addLedger("禁止") }.isFailure); test.finish(test.active()!!.getLong("started") + 90061000)
                    test.edit(null, two, "=表格公式内容", 1700000000000, 1700000001000)
                    val backup = test.backup(); check(test.importBackup(backup).getInt("added") == 0)
                    val bad = JSONObject(backup.toString()); bad.getJSONArray("records").put(JSONObject().put("id", "bad")); check(runCatching { test.importBackup(bad) }.isFailure)
                    check(test.entities("record").size == backup.getJSONArray("records").length())
                    val conflicting = JSONObject(backup.toString()); conflicting.getJSONArray("records").getJSONObject(0).put("task", "另一版本")
                    check(test.importBackup(conflicting).getInt("conflicts") == 1); check(test.importBackup(conflicting).getInt("conflicts") == 0)
                    test.deleteLedger(one); test.importBackup(backup); check(test.entities("record").none { it.getString("ledgerId") == one })
                    check(test.csv(two).contains("'=表格公式内容")); check(durationText(90061000) == "25:01:01")
                    SecretVault(this, "test-credentials").let { vault -> vault.write("lyushi-self-test"); check(vault.read() == "lyushi-self-test"); vault.write("") }
                    File(filesDir, "self-test.json").writeText(JSONObject().put("passed", true).put("checks", "ledger isolation, active lock, import rollback/dedup/conflicts, tombstones, CSV, Keystore").toString(2), Charsets.UTF_8)
                }
                "export" -> File(filesDir, "test-backup.json").writeText(test.backup().toString(2), Charsets.UTF_8)
                "import-desktop" -> Store(this, "interop.sqlite").use { isolated ->
                    val backup = JSONObject(File(filesDir, "import-backup.json").readText(Charsets.UTF_8))
                    val first = isolated.importBackup(backup); val repeated = isolated.importBackup(backup)
                    check(repeated.getInt("added") == 0 && repeated.getInt("conflicts") == 0)
                    File(filesDir, "interop-result.json").writeText(JSONObject().put("first", first).put("repeated", repeated).put("backup", isolated.backup()).toString(2), Charsets.UTF_8)
                }
            }
        }
    }
}
