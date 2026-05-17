package com.attendance.router

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.attendance.router.service.RouterForegroundService
import com.attendance.router.ui.DashboardActivity

/**
 * Entry point of the Router App.
 *
 * Responsibilities:
 *  1. Request all required DANGEROUS runtime permissions.
 *  2. Start RouterForegroundService once permissions are granted.
 *  3. Navigate to DashboardActivity.
 *
 * ANR-safe: service is started with startForegroundService(), which returns
 * immediately. The service itself does all heavy work on background threads.
 *
 * Normal permissions (CHANGE_WIFI_STATE, WAKE_LOCK, FOREGROUND_SERVICE, etc.)
 * are auto-granted at install time — they must NOT be in this list.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnGrant: Button
    private lateinit var tvStatus: TextView

    // Only DANGEROUS permissions go here.
    private val requiredPermissions: Array<String> by lazy {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: NEARBY_WIFI_DEVICES replaces location for WiFi APIs
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                // Notifications are dangerous on API 33+
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Android 8–12: location is required for LocalOnlyHotspot
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            // Legacy storage — only below API 29; API 29+ uses MediaStore
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys.toList()
        if (denied.isEmpty()) {
            onPermissionsGranted()
        } else {
            val names = denied.joinToString(", ") { it.substringAfterLast('.') }
            tvStatus.text = getString(R.string.permissions_denied, names)
            Toast.makeText(this,
                "Some permissions denied — app may not work correctly.",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnGrant = findViewById(R.id.btnGrantPermissions)
        tvStatus  = findViewById(R.id.tvPermissionStatus)

        btnGrant.setOnClickListener { requestRequiredPermissions() }

        if (allPermissionsGranted()) {
            // All permissions already granted — go straight to dashboard.
            tvStatus.text = getString(R.string.permissions_granted)
            btnGrant.isEnabled = false
            onPermissionsGranted()
        } else {
            tvStatus.text = getString(R.string.permissions_required)
        }
    }

    // -------------------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------------------

    private fun requestRequiredPermissions() {
        if (requiredPermissions.isEmpty()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun allPermissionsGranted(): Boolean =
        requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

    // -------------------------------------------------------------------------
    // After permissions are granted
    // -------------------------------------------------------------------------

    private fun onPermissionsGranted() {
        // Start the foreground service.
        // startForegroundService() returns immediately — the service does its
        // heavy init (DB open, hotspot, UDP server) on background threads.
        val serviceIntent = Intent(this, RouterForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Navigate to the dashboard.
        // DashboardActivity observes LiveData/broadcasts from the service —
        // it gracefully handles the brief window before the service is fully ready.
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
