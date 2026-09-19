package com.neighbor.ledger.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.neighbor.ledger.LedgerApp
import com.neighbor.ledger.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「查看原始日志」二级页面：按日期查看 App 私有目录当日原始日志（yyyy-MM-dd.log）。
 */
class LogViewerActivity : AppCompatActivity() {

    private val writer get() = (application as LedgerApp).logWriter
    private lateinit var etDate: TextView
    private lateinit var tvLog: TextView

    private var currentDate: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)
        applySystemBarInsets()
        etDate = findViewById(R.id.et_date)
        tvLog = findViewById(R.id.tv_log)
        findViewById<Button>(R.id.btn_pick_date).setOnClickListener { showPicker() }
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        etDate.setText(currentDate.format(FMT))
        reload()
    }

    private fun showPicker() {
        DatePickerDialog(
            this,
            { _, y, m, d ->
                currentDate = LocalDate.of(y, m + 1, d)
                etDate.setText(currentDate.format(FMT))
                reload()
            },
            currentDate.year, currentDate.monthValue - 1, currentDate.dayOfMonth
        ).show()
    }

    private fun reload() {
        val text = writer.read(currentDate)
        tvLog.text = text.ifBlank {
            getString(R.string.log_empty, currentDate.format(FMT))
        }
        if (text.isBlank()) {
            Toast.makeText(this, R.string.log_empty_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_log)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private companion object {
        val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}