package dev.lyushi.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.abs
import kotlin.math.roundToInt

private val waterZone = ZoneOffset.ofHours(8)
private val waterTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
private fun localTime(at: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(at),waterZone)
private fun waterLabel(kind: String): String = when (kind) {
    "fill" -> "加水"
    "drink" -> "喝水"
    "pour" -> "倒水"
    "manual" -> "补记"
    "setup" -> "设置水壶"
    "capacity" -> "修改容量"
    "calibrate" -> "校准水位"
    "undo" -> "撤销"
    "correct" -> "更正"
    "void" -> "作废"
    "restore-entry" -> "合并历史"
    "restore-bottle" -> "恢复水壶"
    else -> "饮水操作"
}

private fun included(e: JSONObject) = !e.getBoolean("voided") && countsWater(e.getString("kind"))

internal fun Store.waterCsv(): String = buildString {
    append("\ufeff时间（北京时间）,类型,水量（mL）,计入饮水（mL）,状态\r\n")
    for (e in waterEntries(waterView())) {
        val ml=e.getInt("ml")
        append("${localTime(e.getLong("at")).format(waterTime)},${waterLabel(e.getString("kind"))},$ml,${if (included(e)) ml else 0},${if (e.getBoolean("voided")) "已作废" else "有效"}\r\n")
    }
}

/** 刻度与触摸统一使用有效壶身高度。盖子与提环只提供视觉辨识，不参与水量换算。 */
private class BottleView(context: Context) : View(context) {
    private val scale = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()
    private val shape = Path()
    private var capacity = 0
    private var baseline = 0
    private var level = 0
    private var mode = ""
    private var dragging = false
    var moved: (Int) -> Unit = {}

    init { isFocusable = true; contentDescription = "选择操作后拖动蓝色水位线，或使用方向键调整水量" }

    fun state(maximum: Int, original: Int, amount: Int, selection: String) {
        capacity = maximum; baseline = original; level = amount; mode = selection
        invalidate()
    }

    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val x=w/2f; val half=62*scale; val top=53*scale; val bottom=h-14*scale
        body.set(x-half,top,x+half,bottom)
        val left=body.left; val right=body.right; val radius=16*scale
        shape.reset()
        shape.moveTo(x-26*scale,44*scale); shape.lineTo(x+26*scale,44*scale)
        shape.quadTo(x+27*scale,top,right-12*scale,top+8*scale)
        shape.quadTo(right,top+12*scale,right,top+24*scale)
        shape.lineTo(right,bottom-radius); shape.quadTo(right,bottom,right-radius,bottom)
        shape.lineTo(left+radius,bottom); shape.quadTo(left,bottom,left,bottom-radius)
        shape.lineTo(left,top+24*scale); shape.quadTo(left,top+12*scale,left+12*scale,top+8*scale)
        shape.quadTo(x-27*scale,top,x-26*scale,44*scale); shape.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x=body.centerX(); val outline=Color.rgb(105,128,147)
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(232,242,247)
        canvas.drawPath(shape,paint)
        val y=body.bottom-body.height()*(if(capacity==0) 0f else level.toFloat()/capacity)
        canvas.save(); canvas.clipPath(shape)
        paint.shader=LinearGradient(0f,body.top,0f,body.bottom,0xff66c9f5.toInt(),0xff268adb.toInt(),Shader.TileMode.CLAMP)
        canvas.drawRect(body.left,y,body.right,body.bottom,paint); paint.shader=null
        paint.color=0xff176fb6.toInt(); paint.strokeWidth=3*scale
        canvas.drawLine(body.left,y,body.right,y,paint); canvas.restore()
        paint.color=outline; paint.strokeWidth=2*scale; paint.style=Paint.Style.STROKE
        canvas.drawPath(shape,paint)
        canvas.drawRoundRect(x+19*scale,8*scale,x+50*scale,37*scale,12*scale,12*scale,paint)
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(206,227,238)
        canvas.drawRoundRect(x-31*scale,18*scale,x+31*scale,45*scale,7*scale,7*scale,paint)
        paint.color=outline
        for(i in -2..2) canvas.drawLine(x+i*9*scale,23*scale,x+i*9*scale,38*scale,paint)
        for(i in 0..4) {
            val tick=body.bottom-body.height()*i/4
            canvas.drawLine(body.right+8*scale,tick,body.right+15*scale,tick,paint)
        }
        if (mode.isNotEmpty()) {
            val handle=y.coerceIn(body.top+7*scale,body.bottom-7*scale)
            paint.color=Color.WHITE; canvas.drawCircle(x,handle,8*scale,paint)
            paint.color=0xff176fb6.toInt(); canvas.drawCircle(x,handle,6*scale,paint)
        }
    }

    private fun adjust(amount: Int) {
        level=if(mode=="fill") amount.coerceIn(baseline,capacity) else amount.coerceIn(0,baseline)
        invalidate(); moved(level)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode.isEmpty() || capacity==0 || body.height()<=0f) return false
        val target = { (((body.bottom-event.y)/body.height()).coerceIn(0f,1f)*capacity).roundToInt() }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x !in (body.left-14*scale)..(body.right+14*scale) || event.y !in (body.top-10*scale)..(body.bottom+10*scale)) return false
                dragging=true; parent.requestDisallowInterceptTouchEvent(true); adjust(target())
            }
            MotionEvent.ACTION_MOVE -> if (dragging) adjust(target())
            MotionEvent.ACTION_UP -> if (dragging) { dragging=false; adjust(target()); performClick(); parent.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> { dragging=false; parent.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if (mode.isNotEmpty() && (keyCode==KeyEvent.KEYCODE_DPAD_UP || keyCode==KeyEvent.KEYCODE_DPAD_DOWN)) {
            adjust(level+(if(keyCode==KeyEvent.KEYCODE_DPAD_UP)1 else -1)); return true
        }
        return super.onKeyDown(keyCode,event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** 独立窗口随 Activity 销毁，主计时器和原账本页面在窗口后继续更新。 */
internal class WaterDialog(
    private val owner: Activity,
    private val store: Store,
    private val sync: SyncClient,
    private val fileAction: (Int) -> Unit
) : Dialog(owner) {
    private val scale = owner.resources.displayMetrics.density
    private fun dp(value: Int) = (value*scale).roundToInt()
    private var bottle = JSONObject()
    private var draftBottle = JSONObject()
    private var mode = ""
    private var draftML = 0
    private var stale = false
    private val children = mutableListOf<AlertDialog>()
    private val drawing = BottleView(owner)
    private val today = label("",24f)
    private val remaining = label("").apply { gravity=Gravity.CENTER }
    private val preview = label("",14f).apply { gravity=Gravity.CENTER }
    private val status = label("",13f)
    private val capacity = button("水壶设置") { bottleSettings() }
    private val undo = button("撤销上次") { undoOperation() }
    private val conflicts = button("") { review() }
    private val cancel = button("取消预览") { discard(); refresh() }
    private val confirm = button("确认") {
        submit(mode,JSONObject().put("base",draftBottle.getString("version")).put("ml",draftML).put("from",draftBottle.getInt("ml")))
    }
    private val modes = linkedMapOf<String,Button>()

    init {
        setTitle("饮水记录")
        val fields = column().apply { setPadding(dp(18),dp(8),dp(18),dp(10)) }
        val header = row(); header.addView(today,LinearLayout.LayoutParams(0,-2,1f)); header.addView(button("历史") { history() }); fields.addView(header)
        fields.addView(drawing,LinearLayout.LayoutParams(-1,dp(220)))
        fields.addView(remaining)
        val chooser=row()
        for (kind in listOf("fill","drink","pour")) {
            val button=button(waterLabel(kind)) { mode=kind; draftBottle=bottle; draftML=bottle.getInt("ml"); stale=false; refresh() }
            modes[kind]=button; addWeighted(chooser,button)
        }
        fields.addView(chooser); fields.addView(preview)
        fields.addView(row().apply { addWeighted(this,cancel); addWeighted(this,confirm) })
        fields.addView(row().apply { addWeighted(this,capacity); addWeighted(this,undo) })
        fields.addView(row().apply { addWeighted(this,button("备份与导出") { files() }); addWeighted(this,button("返回") { dismiss() }) })
        fields.addView(conflicts); fields.addView(status)
        setContentView(ScrollView(owner).apply { addView(fields) })
        drawing.moved={ draftML=it; updatePreview() }
        refresh()
    }

    override fun onStart() { super.onStart(); window?.setLayout((owner.resources.displayMetrics.widthPixels-dp(20)).coerceAtMost(dp(450)),WindowManager.LayoutParams.WRAP_CONTENT) }
    override fun dismiss() { children.toList().forEach { it.dismiss() }; super.dismiss() }

    private fun row() = LinearLayout(owner).apply { orientation=LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(owner).apply { orientation=LinearLayout.VERTICAL }
    private fun addWeighted(row: LinearLayout,view: View) { row.addView(view,LinearLayout.LayoutParams(0,-2,1f)) }
    private fun label(value: String,size: Float=17f) = TextView(owner).apply { text=value; textSize=size; setPadding(0,dp(4),0,dp(4)) }
    private fun button(value: String,action: () -> Unit) = Button(owner).apply {
        text=value; isAllCaps=false; setOnClickListener { attempt(action) }
    }
    private fun attempt(action: () -> Unit) { try { action() } catch (error: Exception) { Toast.makeText(owner,error.message ?: "饮水操作未完成",Toast.LENGTH_LONG).show() } }
    private fun launch(dialog: AlertDialog) { children.add(dialog); dialog.setOnDismissListener { children.remove(dialog) }; dialog.show() }
    private fun form(title: String,fields: View,save: () -> Unit) {
        val dialog=AlertDialog.Builder(owner).setTitle(title).setView(fields).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        launch(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { attempt { save(); dialog.dismiss() } }
    }
    private fun discard() { mode=""; draftBottle=JSONObject(); stale=false }
    private fun submit(kind: String,fields: JSONObject) { store.waterSubmit(kind,fields); discard(); refresh(); sync.request(true) }

    private fun capacityForm() {
        val captured=bottle
        val input=number(captured.optInt("capacity",500))
        form("水壶容量（mL）",input) {
            val fields=JSONObject().put("capacity",input.text.toString().toIntOrNull() ?: 0).put("ml",0)
            if(captured.length()>0) fields.put("base",captured.getString("version"))
            submit(if(captured.length()==0)"setup" else "capacity",fields)
        }
    }

    /** 以实物为准校准剩余量，校准不产生饮水历史，且可撤销。 */
    private fun bottleSettings() {
        if (bottle.length()==0) { capacityForm(); return }
        launch(AlertDialog.Builder(owner).setTitle("水壶设置").setItems(arrayOf("水壶容量","校准水位")) { _,which ->
            if (which==0) capacityForm() else {
                val captured=bottle; val input=number(captured.getInt("ml"))
                form("当前实际剩余量（mL）",input) {
                    submit("calibrate",JSONObject().put("base",captured.getString("version")).put("ml",input.text.toString().toIntOrNull() ?: -1))
                }
            }
        }.setNegativeButton("取消",null).create())
    }

    private fun number(value: Int) = EditText(owner).apply { setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER; setText(value.toString()) }

    private fun undoOperation() {
        val version=bottle.optString("version")
        launch(AlertDialog.Builder(owner).setTitle("撤销上次操作").setMessage("撤销后恢复上一步的水壶状态，并作废相应明细。是否继续？")
            .setNegativeButton("取消",null).setPositiveButton("撤销") { _,_ -> attempt { submit("undo",JSONObject().put("base",version)) } }.create())
    }

    private fun editHistory(entry: JSONObject?=null,after: () -> Unit) {
        val fields=column().apply { setPadding(dp(18),0,dp(18),0) }
        val amount=number(entry?.getInt("ml") ?: 250); fields.addView(label("饮水量（mL）")); fields.addView(amount)
        val at=EditText(owner).apply { setSingleLine(true); setText(localTime(entry?.getLong("at") ?: System.currentTimeMillis()).format(waterTime)) }
        fields.addView(label("时间（北京时间，年-月-日 时:分:秒）",14f)); fields.addView(at)
        form(if(entry==null)"补记饮水" else "更正饮水",fields) {
            val stamp=try { LocalDateTime.parse(at.text.toString().trim(),waterTime).toInstant(waterZone).toEpochMilli() }
                catch (_: Exception) { error("请输入有效的北京时间，如 2026-11-26 09:30:00") }
            val values=JSONObject().put("ml",amount.text.toString().toIntOrNull() ?: 0).put("at",stamp)
            if(entry!=null) values.put("target",entry.getString("id")).put("base",entry.getString("version"))
            submit(if(entry==null)"manual" else "correct",values); after()
        }
    }

    private fun history() {
        var day=LocalDate.now(waterZone)
        val content=column().apply { setPadding(dp(12),0,dp(12),0) }
        val list=column(); val summary=label("")
        val dayButton=Button(owner).apply { isAllCaps=false }
        val dates=row()
        fun reload() { dayButton.text=day.toString(); updateHistory(list,summary,day) }
        dates.addView(button("前一天") { day=day.minusDays(1); reload() }); addWeighted(dates,dayButton)
        dates.addView(button("后一天") { day=day.plusDays(1); reload() }); content.addView(dates)
        content.addView(summary); content.addView(button("补记饮水") { editHistory(after=::reload) }); content.addView(list)
        // 日期选择回调和历史更正共用重绘，不在后台更新当前编辑表单。
        dayButton.setOnClickListener {
            android.app.DatePickerDialog(owner,{ _,y,m,d -> day=LocalDate.of(y,m+1,d); reload() },day.year,day.monthValue-1,day.dayOfMonth).show()
        }
        reload(); launch(AlertDialog.Builder(owner).setTitle("饮水历史").setView(ScrollView(owner).apply { addView(content) }).setNegativeButton("关闭",null).create())
    }

    private fun updateHistory(list: LinearLayout,summary: TextView,day: LocalDate) {
        list.removeAllViews()
        val rows=waterEntries(store.waterView()).filter { localTime(it.getLong("at")).toLocalDate()==day }
        summary.text="当日饮水 ${rows.filter(::included).sumOf { it.getLong("ml") }} mL"
        if(rows.isEmpty()) list.addView(label("当天暂无记录"))
        for(e in rows) {
            val at=localTime(e.getLong("at")).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            val line="$at　${waterLabel(e.getString("kind"))}　${e.getInt("ml")} mL${if(e.getBoolean("voided")) " · 已作废" else ""}"
            if(!included(e)) list.addView(label(line,15f)) else list.addView(button(line) {
                launch(AlertDialog.Builder(owner).setTitle("饮水记录").setItems(arrayOf("更正","作废")) { _,which ->
                    if(which==0) editHistory(e) { updateHistory(list,summary,day) }
                    else launch(AlertDialog.Builder(owner).setTitle("作废饮水").setMessage("作废后该条记录退出当日统计。确认作废？").setNegativeButton("取消",null)
                        .setPositiveButton("作废") { _,_ -> attempt {
                            submit("void",JSONObject().put("target",e.getString("id")).put("base",e.getString("version"))); updateHistory(list,summary,day)
                        } }.create())
                }.setNegativeButton("关闭",null).create())
            })
        }
    }

    private fun review() {
        val items=store.waterView().optJSONObject("conflicts") ?: return
        val ids=items.keys().asSequence().toList()
        val labels=ids.map {
            val c=items.getJSONObject(it).getJSONObject("command")
            val kind=c.getString("kind")
            val amount=if(kind in listOf("fill","drink","pour")) " · ${abs(c.optInt("from")-c.optInt("ml"))} mL" else ""
            "${localTime(c.getLong("at")).format(waterTime)} · ${waterLabel(kind)}$amount"
        }.toTypedArray()
        launch(AlertDialog.Builder(owner).setTitle("待核对的饮水操作").setItems(labels) { _,idx ->
            val id=ids[idx]
            val builder=AlertDialog.Builder(owner).setTitle("核对操作").setMessage("确认实际饮水后可补记。水位按实物另行调整。")
                .setNegativeButton("取消",null).setNeutralButton("标记已核对") { _,_ -> attempt { submit("dismiss",JSONObject().put("target",id)) } }
            if(items.getJSONObject(id).getJSONObject("command").getString("kind")=="drink") builder.setPositiveButton("确实喝过，补记") { _,_ -> attempt { submit("recover",JSONObject().put("target",id)) } }
            launch(builder.create())
        }.setNegativeButton("关闭",null).create())
    }

    private fun files() {
        launch(AlertDialog.Builder(owner).setTitle("饮水备份与导出").setItems(arrayOf("合并 JSON","导出 JSON","导出 CSV")) { _,which -> fileAction(which) }.setNegativeButton("取消",null).create())
    }

    private fun updatePreview() {
        val current=if(mode.isEmpty()) bottle else draftBottle
        val original=current.optInt("ml"); val amount=if(mode.isEmpty()) original else draftML
        remaining.text=if(current.length()==0)"先设置水壶容量" else "剩余 $amount / ${current.getInt("capacity")} mL"
        preview.text=when {
            stale -> "水壶已在其他设备更新。请取消预览，再依据最新水位操作。"
            mode.isEmpty() -> "选择一种操作，然后拖动蓝色水位线"
            else -> "${waterLabel(mode)} ${abs(amount-original)} mL"
        }
        confirm.isEnabled=mode.isNotEmpty() && !stale && amount!=original
        cancel.isEnabled=mode.isNotEmpty()
    }

    /** 远端变更保留尚未提交的预览并标记过期，确认仍由存储层重新检查。 */
    fun refresh() {
        val data=store.waterView(); bottle=data.optJSONObject("bottle") ?: JSONObject()
        val day=LocalDate.now(waterZone)
        val total=waterEntries(data).filter { included(it) && localTime(it.getLong("at")).toLocalDate()==day }.sumOf { it.getLong("ml") }
        today.text="今日饮水 $total mL"
        if(mode.isNotEmpty() && draftBottle.optString("version")!=bottle.optString("version")) stale=true
        val current=if(mode.isEmpty()) bottle else draftBottle
        drawing.state(current.optInt("capacity"),current.optInt("ml"),if(mode.isEmpty())current.optInt("ml") else draftML,if(stale)"" else mode)
        for((kind,button) in modes) {
            button.text=waterLabel(kind)+(if(mode==kind) " ✓" else "")
            button.isEnabled=bottle.length()>0 && if(kind=="fill") bottle.optInt("ml")<bottle.optInt("capacity") else bottle.optInt("ml")>0
        }
        updatePreview(); capacity.isEnabled=mode.isEmpty(); undo.isEnabled=mode.isEmpty() && bottle.has("undo")
        val pending=data.optJSONArray("pending")?.length() ?: 0
        status.text=sync.waterStatus+(if(pending>0) " · $pending 项待同步" else "")
        val count=data.optJSONObject("conflicts")?.length() ?: 0
        conflicts.visibility=if(count>0) View.VISIBLE else View.GONE; conflicts.text="核对 $count 项饮水操作"
    }
}
