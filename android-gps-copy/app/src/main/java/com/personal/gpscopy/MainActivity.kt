package com.personal.gpscopy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Minimal UI:
 *   - A single status line (Monitoring: Active / Inactive).
 *   - The current coordinate in the exact "lat,lng" format.
 *   - A COPY button so the user can copy straight from inside the app.
 *
 * It also requests the permissions the background trigger needs and fetches a
 * fresh fix whenever it is opened (so opening the app behaves like the GPS-ON
 * trigger).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var coordinateText: TextView
    private lateinit var copyButton: Button
    private lateinit var refreshButton: Button

    private var currentCoordinate: String? = null

    private val permissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) {
            updateStatus()
            maybeFetch()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        coordinateText = findViewById(R.id.coordinateText)
        copyButton = findViewById(R.id.copyButton)
        refreshButton = findViewById(R.id.refreshButton)

        copyButton.setOnClickListener {
            val coord = currentCoordinate
            if (!coord.isNullOrEmpty()) {
                ClipboardUtils.copy(this, coord)
                ClipboardUtils.toast(this, "Copied")
                ClipboardUtils.lightVibrate(this)
            }
        }

        refreshButton.setOnClickListener { maybeFetch() }

        NotificationHelper.ensureChannel(this)
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // Show the last known coordinate immediately if we already have one.
        LocationStore(this).currentCoordinate?.let { showCoordinate(it) }
        maybeFetch()
    }

    /** Fetch a fresh fix for the in-app display when location is enabled + permitted. */
    private fun maybeFetch() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (!isLocationEnabled()) {
            coordinateText.text = "Turn on GPS / Location"
            return
        }

        coordinateText.text = "Getting location..."
        copyButton.isEnabled = false

        // showNotification = false: opening the app should NOT push a status-bar
        // notification; it just shows the coordinate inside the screen.
        LocationFetcher.fetch(
            context = this,
            showNotification = false,
            onResult = { result ->
                runOnUiThread {
                    when (result) {
                        is LocationFetcher.FetchResult.Acquiring ->
                            coordinateText.text = "Getting location..."
                        is LocationFetcher.FetchResult.Success ->
                            showCoordinate(result.coordinate)
                        is LocationFetcher.FetchResult.Failed ->
                            if (currentCoordinate == null) {
                                coordinateText.text = "Location unavailable"
                            }
                    }
                }
            }
        )
    }

    private fun showCoordinate(coordinate: String) {
        currentCoordinate = coordinate
        coordinateText.text = coordinate
        copyButton.isEnabled = true
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            maybeRequestBackgroundLocation()
        }
    }

    /**
     * Background location must be requested separately and only after foreground
     * location is already granted (Android 11+ rule).
     */
    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_BACKGROUND
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
        maybeFetch()
    }

    private fun updateStatus() {
        val active = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        statusText.text = if (active) "Monitoring: Active" else "Monitoring: Inactive"

        // Once foreground location is granted, nudge for "Allow all the time".
        if (active) maybeRequestBackgroundLocation()
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQ_BACKGROUND = 42
    }
}
