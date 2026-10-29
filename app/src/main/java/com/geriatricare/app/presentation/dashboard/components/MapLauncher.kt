package com.geriatricare.app.presentation.dashboard.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Try a map application directly, then a browser. No package visibility query is needed. */
fun openLocationMap(context: Context, appUri: String, browserUrl: String) {
    fun launch(uri: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    if (!launch(appUri) && !launch(browserUrl)) {
        Toast.makeText(context, "Install a map app or browser to open this location.", Toast.LENGTH_LONG).show()
    }
}

fun isValidGpsCoordinate(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
