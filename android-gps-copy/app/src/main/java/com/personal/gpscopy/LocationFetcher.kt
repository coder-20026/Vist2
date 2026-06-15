package com.personal.gpscopy

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * One-shot location fetch. Used by:
 *   - ProvidersChangedReceiver (background, GPS-ON trigger -> posts notification)
 *   - MainActivity              (foreground, shows the coordinate inside the app)
 *
 * Lifecycle:
 *   1. Report Acquiring + (optionally) post the "Getting location..." notification.
 *   2. Request ONE fresh high-accuracy fix (rejects stale cached results).
 *   3. If no fresh fix arrives within the soft timeout, fall back to the last
 *      known location so the user still gets a coordinate.
 *   4. Validate -> format -> dedupe -> report Success + replace notification + arm 10-min alarm.
 *   5. Call onComplete() so the receiver can release goAsync().
 */
object LocationFetcher {

    private const val TAG = "LocationFetcher"

    /** Progress/result reported back to whoever started the fetch (e.g. the UI). */
    sealed interface FetchResult {
        /** Waiting for the first valid fix. */
        object Acquiring : FetchResult
        /** A valid coordinate string in the exact "lat,lng" format. */
        data class Success(val coordinate: String) : FetchResult
        /** No usable fix could be obtained. */
        object Failed : FetchResult
    }

    /**
     * @param showNotification post / update the status-bar notification.
     * @param onResult         progress + result callback, invoked on the main thread.
     * @param onComplete       called exactly once when the fetch is fully finished.
     */
    fun fetch(
        context: Context,
        showNotification: Boolean = true,
        onResult: ((FetchResult) -> Unit)? = null,
        onComplete: () -> Unit = {}
    ) {
        val appCtx = context.applicationContext

        if (ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No location permission; aborting.")
            onResult?.invoke(FetchResult.Failed)
            onComplete()
            return
        }

        val store = LocationStore(appCtx)
        val notifications = NotificationManagerCompat.from(appCtx)
        val fused = LocationServices.getFusedLocationProviderClient(appCtx)
        val cts = CancellationTokenSource()
        val handler = Handler(Looper.getMainLooper())
        var done = false

        // We're waiting for the first valid fix.
        onResult?.invoke(FetchResult.Acquiring)

        // Show the placeholder right away (no-op on Android 13+ without permission).
        if (showNotification) {
            store.notificationActive = true
            try {
                notifications.notify(
                    Constants.NOTIFICATION_ID,
                    NotificationHelper.buildGettingLocation(appCtx)
                )
            } catch (_: SecurityException) {
            }
        }

        fun complete() {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            cts.cancel()
            onComplete()
        }

        fun publish(location: Location?) {
            if (done) return

            // Reject invalid / obviously bad fixes (kept internal, never shown).
            val bad = location == null ||
                (location.hasAccuracy() && location.accuracy > Constants.MAX_ACCEPTABLE_ACCURACY_M) ||
                (location.latitude == 0.0 && location.longitude == 0.0)

            if (bad) {
                Log.w(TAG, "Rejected fix (null/low-accuracy).")
                // Nothing valid to show yet -> take the placeholder down.
                if (showNotification && store.currentCoordinate == null) {
                    store.notificationActive = false
                    notifications.cancel(Constants.NOTIFICATION_ID)
                }
                onResult?.invoke(FetchResult.Failed)
                complete()
                return
            }

            val coordinate = CoordinateFormatter.format(location!!.latitude, location.longitude)

            store.currentCoordinate = coordinate
            store.lastCoordinate = coordinate

            // Always report the coordinate to the UI, even if it's a duplicate.
            onResult?.invoke(FetchResult.Success(coordinate))

            if (showNotification) {
                store.notificationActive = true
                try {
                    notifications.notify(
                        Constants.NOTIFICATION_ID,
                        NotificationHelper.buildCoordinate(appCtx, coordinate)
                    )
                } catch (_: SecurityException) {
                }
                scheduleAutoCopy(appCtx, coordinate)
            }

            complete()
        }

        // Soft timeout: fall back to the last known location, then give up.
        handler.postDelayed({
            if (done) return@postDelayed
            try {
                fused.lastLocation
                    .addOnSuccessListener { last -> publish(last) }
                    .addOnFailureListener { publish(null) }
            } catch (_: SecurityException) {
                publish(null)
            }
        }, Constants.FIX_SOFT_TIMEOUT_MS)

        // CurrentLocationRequest forces a *fresh* computation and rejects stale
        // cached results, satisfying the "prefer fresh fix" requirement.
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0) // do not accept old cached fixes
            .setDurationMillis(Constants.FIX_SOFT_TIMEOUT_MS)
            .build()

        try {
            fused.getCurrentLocation(request, cts.token)
                .addOnSuccessListener { location -> publish(location) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "getCurrentLocation failed; waiting for fallback.", e)
                    // Let the soft-timeout fallback try lastLocation.
                }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException requesting location", se)
            publish(null)
        }
    }

    /** Arm the 10-minute "no action -> auto copy + dismiss" alarm. */
    private fun scheduleAutoCopy(context: Context, coordinate: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ActionReceiver::class.java).apply {
            action = Constants.ACTION_AUTO_COPY
            putExtra(Constants.EXTRA_COORDINATE, coordinate)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, 2, intent, flags)

        val triggerAt = SystemClock.elapsedRealtime() + Constants.AUTO_COPY_TIMEOUT_MS
        // Inexact + allow-while-idle: battery friendly, no exact-alarm permission needed.
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
    }
}
