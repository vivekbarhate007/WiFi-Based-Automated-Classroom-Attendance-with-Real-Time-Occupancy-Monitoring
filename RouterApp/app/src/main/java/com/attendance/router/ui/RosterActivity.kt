package com.attendance.router.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.attendance.router.R
import com.attendance.router.db.AppDatabase
import com.attendance.router.db.RosterEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manage the class roster (the list of students who can check in).
 *
 * Features:
 *   - Import a CSV ("rollNo,name" per line) via the system file picker
 *   - Load a bundled sample roster for demos / first-run
 *   - Clear the roster
 *   - View the current roster as a scrollable list
 *
 * The roster is persisted in Room and used by [CheckInHttpServer] to validate
 * submissions and by the attendance engine to compute who counts as enrolled.
 */
class RosterActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RosterActivity"

        /** Hard cap on CSV rows so a runaway file can't OOM the app. */
        private const val MAX_ROWS = 2_000
    }

    private lateinit var db: AppDatabase

    private lateinit var tvRosterCount: TextView
    private lateinit var tvRosterEmpty: TextView
    private lateinit var rvRoster: RecyclerView
    private val adapter = RosterAdapter()

    /** System file picker (SAF). Returns the URI of the chosen CSV, or null. */
    private val csvPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> importCsvFromUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roster)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        db = AppDatabase.getInstance(applicationContext)

        tvRosterCount = findViewById(R.id.tvRosterCount)
        tvRosterEmpty = findViewById(R.id.tvRosterEmpty)
        rvRoster      = findViewById(R.id.rvRoster)
        rvRoster.layoutManager = LinearLayoutManager(this)
        rvRoster.adapter = adapter

        findViewById<View>(R.id.btnImportCsv).setOnClickListener { launchCsvPicker() }
        findViewById<View>(R.id.btnLoadSample).setOnClickListener { confirmLoadSample() }
        findViewById<View>(R.id.btnClearRoster).setOnClickListener { confirmClear() }

        // Observe the roster live — updates count + list as the DB changes.
        db.rosterDao().getAllLive().observe(this) { list -> render(list ?: emptyList()) }
    }

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    private fun render(list: List<RosterEntry>) {
        tvRosterCount.text = getString(R.string.roster_count_fmt, list.size)
        if (list.isEmpty()) {
            tvRosterEmpty.visibility = View.VISIBLE
            rvRoster.visibility      = View.GONE
        } else {
            tvRosterEmpty.visibility = View.GONE
            rvRoster.visibility      = View.VISIBLE
        }
        adapter.submit(list)
    }

    // -------------------------------------------------------------------------
    // CSV import
    // -------------------------------------------------------------------------

    private fun launchCsvPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Accept a few likely mime types that CSVs get labelled with.
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/csv", "text/comma-separated-values", "text/plain",
                "application/csv", "application/vnd.ms-excel"
            ))
        }
        csvPicker.launch(intent)
    }

    private fun importCsvFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entries = parseCsv(uri)
                if (entries.isEmpty()) {
                    toastOnMain("No valid rows found in CSV.")
                    return@launch
                }
                db.rosterDao().insertAll(entries)
                toastOnMain("Imported ${entries.size} students.")
                Log.i(TAG, "CSV import OK: ${entries.size} rows from $uri")
            } catch (e: Exception) {
                Log.e(TAG, "CSV import failed: ${e.message}", e)
                toastOnMain("Import failed: ${e.message}")
            }
        }
    }

    /**
     * Parses a CSV stream into [RosterEntry] rows. Rules:
     *   - Blank lines and lines starting with '#' are skipped
     *   - An optional header line (contains "rollno" / "name" / "roll") is skipped
     *   - Both "," and ";" are accepted as delimiters
     *   - Roll numbers are uppercased and trimmed; names are trimmed
     *   - Duplicate roll numbers keep the last one seen
     */
    private fun parseCsv(uri: Uri): List<RosterEntry> {
        val seen = linkedMapOf<String, String>()  // preserves first-seen order

        contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            var lineNum = 0
            for (raw in reader.lineSequence()) {
                lineNum++
                if (seen.size >= MAX_ROWS) break
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                val parts = line.split(',', ';').map { it.trim() }
                if (parts.size < 2) continue
                val rollNo = parts[0].uppercase()
                val name   = parts[1]
                if (rollNo.isBlank() || name.isBlank()) continue

                // Detect and skip header row (e.g. "rollNo,name")
                if (lineNum == 1 && (
                        rollNo.equals("rollno", ignoreCase = true) ||
                        rollNo.equals("roll", ignoreCase = true) ||
                        rollNo.equals("roll no", ignoreCase = true) ||
                        rollNo.equals("id", ignoreCase = true)
                    )) {
                    continue
                }

                seen[rollNo] = name
            }
        } ?: throw IllegalStateException("Could not open CSV file.")

        return seen.map { (rollNo, name) -> RosterEntry(rollNo = rollNo, name = name) }
    }

    // -------------------------------------------------------------------------
    // Sample roster
    // -------------------------------------------------------------------------

    private fun confirmLoadSample() {
        AlertDialog.Builder(this)
            .setTitle("Load Sample Roster")
            .setMessage("This will add 10 demo students to the roster. " +
                    "Existing entries with the same roll numbers will be overwritten. Continue?")
            .setPositiveButton("Load") { _, _ -> loadSample() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSample() {
        val sample = listOf(
            RosterEntry("CS001", "Aarav Patel"),
            RosterEntry("CS002", "Vivek Barhate"),
            RosterEntry("CS003", "Priya Sharma"),
            RosterEntry("CS004", "Rohan Mehta"),
            RosterEntry("CS005", "Ananya Iyer"),
            RosterEntry("CS006", "Kunal Desai"),
            RosterEntry("CS007", "Sneha Kapoor"),
            RosterEntry("CS008", "Arjun Reddy"),
            RosterEntry("CS009", "Isha Verma"),
            RosterEntry("CS010", "Rahul Singh"),
            RosterEntry("CS011", "Kavya Nair"),
            RosterEntry("CS012", "Siddharth Joshi"),
            RosterEntry("CS013", "Meera Pillai"),
            RosterEntry("CS014", "Aditya Kumar"),
            RosterEntry("CS015", "Pooja Agarwal"),
            RosterEntry("CS016", "Nikhil Chandra"),
            RosterEntry("CS017", "Divya Menon"),
            RosterEntry("CS018", "Karan Malhotra"),
            RosterEntry("CS019", "Shreya Gupta"),
            RosterEntry("CS020", "Varun Tiwari"),
            RosterEntry("CS021", "Nisha Rao"),
            RosterEntry("CS022", "Abhishek Saxena"),
            RosterEntry("CS023", "Ritu Pandey"),
            RosterEntry("CS024", "Manish Sinha"),
            RosterEntry("CS025", "Deepika Shah"),
            RosterEntry("CS026", "Tarun Bose"),
            RosterEntry("CS027", "Simran Kaur"),
            RosterEntry("CS028", "Yash Trivedi"),
            RosterEntry("CS029", "Anjali Mishra"),
            RosterEntry("CS030", "Suresh Yadav"),
            RosterEntry("CS031", "Latha Krishnan"),
            RosterEntry("CS032", "Omkar Patil"),
            RosterEntry("CS033", "Farida Sheikh"),
            RosterEntry("CS034", "Harsh Vardhan"),
            RosterEntry("CS035", "Tanvi Bhatt"),
        )
        lifecycleScope.launch(Dispatchers.IO) {
            db.rosterDao().insertAll(sample)
            toastOnMain("Sample roster loaded (${sample.size} students).")
        }
    }

    // -------------------------------------------------------------------------
    // Clear roster
    // -------------------------------------------------------------------------

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear Roster")
            .setMessage("Delete all students from the roster? Check-ins for existing sessions " +
                    "will keep their roll numbers but may no longer resolve to names.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.rosterDao().clear()
                    toastOnMain("Roster cleared.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun toastOnMain(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@RosterActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

// -----------------------------------------------------------------------------
// Adapter
// -----------------------------------------------------------------------------

private class RosterAdapter :
    RecyclerView.Adapter<RosterAdapter.VH>() {

    private var rows: List<RosterEntry> = emptyList()

    fun submit(newRows: List<RosterEntry>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_roster, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvRoll: TextView = v.findViewById(R.id.tvRollNo)
        private val tvName: TextView = v.findViewById(R.id.tvRosterName)
        fun bind(e: RosterEntry) {
            tvRoll.text = e.rollNo
            tvName.text = e.name
        }
    }
}
