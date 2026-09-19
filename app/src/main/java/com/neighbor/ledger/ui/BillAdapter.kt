package com.neighbor.ledger.ui

import android.view.LayoutInflater
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.neighbor.ledger.R
import com.neighbor.ledger.data.Bill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 账单条目适配器。
 * 排序：时间升序（早在上、晚在下）；结构：+/- 符号、金额、备注长文本、时间小字。
 * 交互：双击条目 → 重命名备注。
 */
class BillAdapter(
    private val onDoubleTap: (Bill) -> Unit,
    private val onSwipeDay: (Int) -> Unit,
    private val onLongPress: (Bill) -> Unit
) : RecyclerView.Adapter<BillAdapter.VH>() {

    private var items: List<Bill> = emptyList()
    private var lastTapId = -1L
    private var lastTapAt = 0L

    fun submit(list: List<Bill>) {
        // 时间升序（Room 已按 timestamp ASC，双保险）
        items = list.sortedBy { it.timestamp }
        val ids = items.map { it.id }.toSet()
        selectedIds.retainAll(ids) // 只保留仍存在的选中项
        notifyDataSetChanged()
    }

    // ---- 长按多选 · 批量管理 ----
    var selectionMode = false
        private set
    val selectedIds = mutableSetOf<Long>()
    var onSelectionChanged: ((Int, Boolean) -> Unit)? = null

    fun enterSelection(first: Bill) {
        selectionMode = true
        selectedIds.add(first.id)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size, true)
    }

    fun toggle(bill: Bill) {
        if (!selectedIds.add(bill.id)) selectedIds.remove(bill.id)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size, selectionMode)
    }

    fun selectAll() {
        selectedIds.addAll(items.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size, selectionMode)
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0, false)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val sign: TextView = view.findViewById(R.id.item_sign)
        val amount: TextView = view.findViewById(R.id.item_amount)
        val note: TextView = view.findViewById(R.id.item_note)
        val time: TextView = view.findViewById(R.id.item_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bill, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = items[position]
        val ctx = holder.itemView.context
        val green = ContextCompat.getColor(ctx, R.color.saving_green)
        val red = ContextCompat.getColor(ctx, R.color.over_red)

        if (b.type == "income") {
            holder.sign.text = "+"
            holder.sign.setTextColor(green)
            holder.amount.text = "%.2f".format(b.amount)
            holder.amount.setTextColor(green)
        } else {
            holder.sign.text = "‑"
            holder.sign.setTextColor(red)
            holder.amount.text = "%.2f".format(b.amount)
            holder.amount.setTextColor(red)
        }
        // 用户备注优先，否则用解析备注
        holder.note.text = b.userRemark.ifBlank { b.note }
        holder.time.text = TIME_FMT.format(
            Instant.ofEpochMilli(b.timestamp).atZone(ZoneId.systemDefault())
        )

        // 选中态视觉：多选模式下被选中条目用品牌色描边的小圆角方框
        val selected = selectionMode && selectedIds.contains(b.id)
        holder.itemView.setBackgroundResource(if (selected) R.drawable.bg_card_selected else R.drawable.bg_card)

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggle(b)
            } else {
                val now = SystemClock.elapsedRealtime()
                if (lastTapId == b.id && now - lastTapAt < 300L) {
                    lastTapId = -1L
                    onDoubleTap(b)
                } else {
                    lastTapAt = now
                    lastTapId = b.id
                }
            }
        }
        // 长按弹出操作菜单（编辑 / 删除 / 多选）；返回 true 抑制随后的 click。
        holder.itemView.setOnLongClickListener {
            onLongPress(b)
            true
        }

        // 左右滑动条目 → 视觉跟随 + 切换上一日/下一日（点击仍走上面的双击重命名）。
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var hx0 = 0f
        var hy0 = 0f
        var horizontal = false
        holder.itemView.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hx0 = e.x; hy0 = e.y; horizontal = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - hx0
                    val dy = e.y - hy0
                    if (!horizontal && abs(dx) > slop && abs(dx) > abs(dy)) {
                        horizontal = true
                        (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) {
                        v.translationX = dx * 0.5f // 卡片跟着手指
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - hx0
                    if (horizontal) {
                        val wasSwipe = abs(dx) > slop && abs(dx) > abs(e.y - hy0)
                        if (wasSwipe) onSwipeDay(if (dx < 0) 1 else -1)
                        horizontal = false
                        (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        v.animate().translationX(0f).setDuration(120L).start()
                        true // 横向拖动过就吞掉本次点击，避免误触发重命名
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    horizontal = false
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    v.animate().translationX(0f).setDuration(120L).start()
                    false
                }
                else -> false
            }
        }
    }

    

    private companion object {
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}