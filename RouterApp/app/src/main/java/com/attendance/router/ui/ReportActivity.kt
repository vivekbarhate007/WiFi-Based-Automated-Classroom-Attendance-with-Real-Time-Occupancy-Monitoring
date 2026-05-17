package com.attendance.router.ui

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.attendance.router.R
import com.attendance.router.db.AppDatabase
import com.attendance.router.db.AttendanceResult
import com.attendance.router.db.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Shows the attendance report for a selected session and provides CSV export.
 */
class ReportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ReportActivity"
        private const val DEFAULT_CLASS_DURATION_MIN = 50
    }

    private val viewModel: SessionViewModel by viewModels()
    private lateinit var db: AppDatabase

    private lateinit var spinnerSessions: Spinner
    private lateinit var etClassDuration: EditText
    private lateinit var btnGenerateReport: Button
    private lateinit var btnExportCsv: Button
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var rvReport: RecyclerView

    private val reportAdapter = ReportAdapter()
    private var sessions: List<Session> = emptyList()
    private var selectedSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        db = AppDatabase.getInstance(applicationContext)

        // Wire the toolbar back arrow. The layout draws the arrow, but without
        // this listener tapping it does nothing.
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupRecyclerView()
        observeViewModel()
        loadSessions()
    }

    private fun initViews() {
        spinnerSessions = findViewById(R.id.spinnerSessions)
        etClassDuration = findViewById(R.id.etClassDuration)
        btnGenerateReport = findViewById(R.id.btnGenerateReport)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        tvSummary = findViewById(R.id.tvReportSummary)
        tvEmpty = findViewById(R.id.tvReportEmpty)
        rvReport = findViewById(R.id.rvReport)

        etClassDuration.setText(DEFAULT_CLASS_DURATION_MIN.toString())

        btnGenerateReport.setOnClickListener {
            val sessionId = selectedSessionId
            if (sessionId == null) {
                Toast.makeText(this, "Please select a session", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val duration = etClassDuration.text.toString().toIntOrNull() ?: DEFAULT_CLASS_DURATION_MIN
            viewModel.generateReport(sessionId, duration)
        }

        btnExportCsv.setOnClickListener {
            exportCsv()
        }

        btnExportCsv.isEnabled = false
    }

    private fun setupRecyclerView() {
        rvReport.layoutManager = LinearLayoutManager(this)
        rvReport.adapter = reportAdapter
    }

    private fun observeViewModel() {
        viewModel.reportResults.observe(this) { results ->
            if (results.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvReport.visibility = View.GONE
                btnExportCsv.isEnabled = false
            } else {
                tvEmpty.visibility = View.GONE
                rvReport.visibility = View.VISIBLE
                reportAdapter.submitList(results)
                btnExportCsv.isEnabled = true
            }
        }

        viewModel.reportSummary.observe(this) { summary ->
            tvSummary.text = summary
        }

        viewModel.statusMessage.observe(this) { msg ->
            if (msg.isNotBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSessions() {
        CoroutineScope(Dispatchers.IO).launch {
            val loadedSessions = db.sessionDao().getAll()
            withContext(Dispatchers.Main) {
                sessions = loadedSessions
                val labels = sessions.map { session ->
                    val endedStr = if (session.endTime != null) " (ended)" else " (active)"
                    "${session.courseName}$endedStr — ${session.sessionId.take(8)}"
                }

                val adapter = ArrayAdapter(
                    this@ReportActivity,
                    android.R.layout.simple_spinner_item,
                    labels
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerSessions.adapter = adapter

                spinnerSessions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                        selectedSessionId = sessions.getOrNull(position)?.sessionId
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) {
                        selectedSessionId = null
                    }
                }

                if (sessions.isEmpty()) {
                    tvEmpty.text = getString(R.string.no_sessions_found)
                    tvEmpty.visibility = View.VISIBLE
                    btnGenerateReport.isEnabled = false
                }
            }
        }
    }

    /**
     * Saves the generated CSV to the Downloads folder.
     * Uses MediaStore on Android Q+ and direct file write on older versions.
     */
    private fun exportCsv() {
        val csv = viewModel.reportCsv.value
        if (csv.isNullOrBlank()) {
            Toast.makeText(this, "No report data to export. Generate a report first.", Toast.LENGTH_SHORT).show()
            return
        }

        val sessionId = selectedSessionId ?: "unknown"
        val fileName = "attendance_${sessionId}_${System.currentTimeMillis()}.csv"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { stream ->
                            stream.write(csv.toByteArray(Charsets.UTF_8))
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ReportActivity, "Exported to Downloads/$fileName", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ReportActivity, "Failed to create file in Downloads", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    downloadsDir.mkdirs()
                    val file = File(downloadsDir, fileName)
                    FileOutputStream(file).use { stream ->
                        stream.write(csv.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ReportActivity, "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "CSV export failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ReportActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Report RecyclerView Adapter
// -------------------------------------------------------------------------

class ReportAdapter : RecyclerView.Adapter<ReportAdapter.ReportViewHolder>() {

    private var items: List<AttendanceResult> = emptyList()

    fun submitList(newItems: List<AttendanceResult>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_student, parent, false)
        return ReportViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ReportViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvStudentName)
        private val tvStudentId: TextView = itemView.findViewById(R.id.tvStudentId)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvAttendanceStatus)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvPercentage: TextView = itemView.findViewById(R.id.tvPercentage)

        fun bind(result: AttendanceResult) {
            tvName.text = result.name
            tvStudentId.text = result.studentId
            tvStatus.text = result.status
            tvDuration.text = "${result.durationMinutes} min"
            tvPercentage.text = "${"%.1f".format(result.percentage * 100f)}%"

            // Color-code the status for quick visual scanning
            val statusColor = when (result.status) {
                "PRESENT" -> 0xFF1B5E20.toInt()   // dark green
                "PARTIAL"  -> 0xFFE65100.toInt()   // dark orange
                else       -> 0xFFB71C1C.toInt()   // dark red
            }
            tvStatus.setTextColor(statusColor)
        }
    }
}
