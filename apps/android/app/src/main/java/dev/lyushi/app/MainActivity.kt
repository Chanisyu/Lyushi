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
    private val locked = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private var selected = ""
    private var screen = "main"
    private var busy = false
    private var fileMode = 0
    private var actionRecordAtPress: String? = null
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
        store = Store(this); selected = preferences.getString("ledger", "") ?: ""; fileMode = savedInstanceState?.getInt("fileMode") ?: 0
        sync = SyncClient(applicationContext, store, preferences, worker, handler) { changed, reconnected ->
            if (!isDestroyed) safely {
                if (changed && !busy) {
                    // 远端仅改变标签或账本时保留尚未开始的事项草稿，不影响进行中状态接管。
                    val draft = if (screen == "main" && task.isEnabled && store.active() == null) task.text.toString() else null
                    if (store.active() != null) dialogs.toList().forEach { it.dismiss() }
                    showMain(); if (draft != null) task.setText(draft)
                }
                else if (screen == "main") render()
                if (reconnected) Toast.makeText(this, "已重新连接服务器，双方内容均已保留；冲突可在结束记录后查看。", Toast.LENGTH_LONG).show()
            }
        }
        showMain()
        // 调试入口只使用另一份私有测试数据库，不清除或填充用户的正式记录。
        if (BuildConfig.DEBUG && intent.hasExtra("test_action")) safely { debugTest(intent.getStringExtra("test_action")!!) }
    }
    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("fileMode", fileMode); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        sync.close(); dialogs.toList().forEach { it.dismiss() }
        // 排在全部文件操作之后关闭数据库，避免后台任务与页面销毁竞争句柄。
        worker.execute { store.close() }; worker.shutdown(); super.onDestroy()
    }
    @Deprecated("系统旧版返回回调")
    override fun onBackPressed() { if (busy) return; if (screen != "main") showMain() else super.onBackPressed() }
    /** 统一处理用户可恢复错误，失败不会被显示为保存成功。 */
    private fun safely(block: () -> Unit) { try { block() } catch (e: Exception) { Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show() } }
    /** 跟踪对话框，远端开始计时时关闭管理表单，执行全设备已知的记录期间限制。 */
    private fun AlertDialog.present() { dialogs.add(this); setOnDismissListener { dialogs.remove(this) }; show() }
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
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (store.active() == null && position in ledgers.indices) { selected = ledgers[position].getString("id"); preferences.edit().putString("ledger", selected).apply() } }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(ledgerSpinner); locked.add(ledgerSpinner)
        root.addView(button("新建账本") { idle(); promptName("新建账本", "") { selected = store.addLedger(it); showMain() } }.also { locked.add(it) })
        task = EditText(this).apply { hint = "现在正在做什么？"; maxLines = 4; setText(current?.getString("task") ?: "") }; root.addView(task)
        // 新增及删除入口位于下拉框右侧；管理标签时保留尚未提交的事项输入。
        root.addView(label("标签（可选）", 14f))
        val tagRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tagSpinner = tagChoice(current?.optString("tag") ?: selectedTag, current != null)
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
            check(intended == current) { "计时状态已由其他设备改变，请核对页面后再操作" }
            if (store.active() == null) store.start(selected, task.text.toString(), chosenTag(tagSpinner)) else { store.finish(); task.setText("") }; showMain(); sync.request(true)
        }
        // 返回 false，让系统保留正常点击与无障碍行为；这里只捕获用户按下时的操作对象。
        action.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) actionRecordAtPress = store.active()?.getString("id") ?: ""
            else if (event.action == android.view.MotionEvent.ACTION_CANCEL) actionRecordAtPress = null
            false
        }; root.addView(action)
        root.addView(button("账本明细与管理") { idle(); check(selected.isNotEmpty()) { "请先新建账本" }; showDetails() }.also { locked.add(it) })
        root.addView(button("从 JSON 备份恢复") { chooseFile(0) }.also { locked.add(it) })
        root.addView(button("设置") { check(!busy); showSettings() })
        root.addView(button("立即同步") { sync.request(true) })
        root.addView(button("查看保留的冲突版本") { showConflicts() }.also { locked.add(it) })
        status = label("", 13f); root.addView(status); render()
    }
    /** 下拉项与显示文字分离，已删除快照不会被误认为可供新任务使用的标签。 */
    private fun tagChoice(initial: String, keepHistorical: Boolean = false): Spinner {
        val names = store.entities("tag").map { it.getString("name") }.distinct().sorted()
        val values = listOf("") + names + if (keepHistorical && initial.isNotEmpty() && initial !in names) listOf(initial) else emptyList()
        return Spinner(this).apply {
            tag = values
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values.map {
                if (it.isEmpty()) "无标签" else if (it !in names) "$it（已删除）" else it
            })
            setSelection(values.indexOf(initial).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (screen == "main" && store.active() == null) selectedTag = values[position] }
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
        action.isEnabled = !busy && (running || selected.isNotEmpty()); action.text = if (running) "结束记录" else "开始记录"
        if (current != null && task.text.toString() != current.getString("task")) task.setText(current.getString("task"))
        elapsed.text = if (current == null) "00:00:00" else durationText(now - current.getLong("started"))
        status.text = if (busy) "正在处理文件…" else if (current != null && now < current.getLong("started")) "设备时间早于开始时间，请核对系统时间"
            else sync.status + if (store.hasConflicts()) " · 已保留冲突版本" else ""
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
        records.forEach { record -> root.addView(button(record.getString("task") + "\n" + dateText(record.getLong("started")) + " → " + dateText(record.getLong("ended")) + "\n" + durationText(record.getLong("ended") - record.getLong("started")) + "\n标签：" + record.optString("tag", "无标签")) {
            idle(); AlertDialog.Builder(this).setTitle(record.getString("task")).setItems(arrayOf("修改", "删除")) { _, which -> safely {
                if (which == 0) edit(record) else AlertDialog.Builder(this).setTitle("删除记录").setMessage("确认删除这条记录？")
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
    private fun edit(record: JSONObject?) {
        idle(); val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        val taskInput = EditText(this).apply { hint = "事项"; setText(record?.getString("task") ?: "") }; fields.addView(taskInput)
        fields.addView(label("标签", 14f)); val recordTag = tagChoice(record?.optString("tag") ?: "", true); fields.addView(recordTag)
        fun timeInput(title: String, value: Long): EditText { fields.addView(label(title, 14f)); return EditText(this).apply { setSingleLine(true); setText(dateText(value)); fields.addView(this) } }
        val begin = timeInput("开始（北京时间）", record?.getLong("started") ?: (System.currentTimeMillis() - 60000))
        val end = timeInput("结束（北京时间）", record?.getLong("ended") ?: System.currentTimeMillis())
        val dialog = AlertDialog.Builder(this).setTitle(if (record == null) "补记" else "修改记录").setView(fields).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely {
            idle(); val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
            fun parse(input: EditText) = LocalDateTime.parse(input.text.toString(), format).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
            store.edit(record?.getString("id"), selected, taskInput.text.toString(), parse(begin), parse(end), record?.optString("version"), chosenTag(recordTag)); dialog.dismiss(); showDetails()
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
            root.addView(label(description, 16f).apply { setTextIsSelectable(true) })
        }
        root.addView(button("返回") { showMain() })
    }
    /** 密钥通过 Keystore 单独保存，不写普通设置、不带入备份；记录时锁定连接设置。 */
    private fun showSettings() {
        screen = "settings"; page("设置"); root.addView(label("语言：简体中文"))
        root.addView(label("版本 ${BuildConfig.VERSION_NAME}（测试版）",14f))
        root.addView(label("主题", 14f)); val theme = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("跟随系统", "白天", "黑夜")); setSelection(preferences.getInt("theme", 0)) }; root.addView(theme)
        // 服务器地址由用户填写，空默认值不覆盖既有配置。
        val server = EditText(this).apply { hint = "服务器地址 https://"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setText(preferences.getString("server", "")); isEnabled = store.active() == null }; root.addView(server)
        val key = EditText(this).apply { hint = "设备密钥"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isEnabled = store.active() == null }; root.addView(key)
        var keyLoaded = false
        safely { key.setText(SecretVault(this).read()); keyLoaded = true }
        root.addView(label("填写设备密钥后自动同步；离线仍可记录。密钥存入系统安全存储，冲突双方分别保留。", 14f))
        root.addView(button("保存设置") {
            val address = server.text.toString().trim()
            if (store.active() == null) { check(address.isEmpty() || android.net.Uri.parse(address).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null && it.query == null }) { "请输入不含用户名、密码、查询或片段的 HTTPS 地址" }; check(keyLoaded || key.text.isNotEmpty()) { "原密钥未能读取，请重新填写" }; val secret = key.text.toString().trim(); check(secret.isEmpty() || Regex("^[0-9a-f]{64}$").matches(secret)) { "设备密钥应为 64 位小写十六进制文字" }; SecretVault(this).write(secret) }
            check(preferences.edit().putInt("theme", theme.selectedItemPosition).putString("server", address).commit()) { "设置保存失败" }; recreate()
        }); root.addView(button("返回") { showMain() })
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
                val result = runCatching { Store(this, "network-test.sqlite").use { isolated ->
                    when (command.getString("action")) {
                        "seed" -> isolated.start(isolated.addLedger("三端验收"), "跨进程恢复")
                        "finish" -> isolated.finish()
                        "edit" -> { val record = isolated.entities("record").first { !it.isNull("ended") && !it.getBoolean("deleted") }; isolated.edit(record.getString("id"), record.getString("ledgerId"), command.getString("task"), record.getLong("started"), record.getLong("ended")) }
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
