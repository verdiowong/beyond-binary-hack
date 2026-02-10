package com.example.emergencyresponse

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

class LocationHandler(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("emergency_user_profile", Context.MODE_PRIVATE)

    private var locationCallback: LocationCallback? = null

    fun requestHighAccuracyLocation(onAddressReady: (String) -> Unit, onError: (String) -> Unit) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Missing ACCESS_FINE_LOCATION permission.")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMaxUpdateDelayMillis(3_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                // Member 4 TODO: Use Geocoder for reverse-geocoding coordinates into
                // a Singapore street address (e.g., Blk 889 Tampines St 81).
                val geocoder = Geocoder(context, Locale("en", "SG"))
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val formattedAddress = addresses?.firstOrNull()?.getAddressLine(0)
                    ?: "${location.latitude}, ${location.longitude}"

                onAddressReady(formattedAddress)
                stopLocationUpdates()
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback as LocationCallback,
            Looper.getMainLooper()
        )
    }

    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
        }
        locationCallback = null
    }

    // Member 4 TODO: Build a simple SharedPrefs or Room data store for the
    // user's unit number and medical ID.
    fun saveUserProfile(unitNumber: String, medicalId: String) {
        prefs.edit()
            .putString("unit_number", unitNumber)
            .putString("medical_id", medicalId)
            .apply()
    }

    fun loadUserProfile(): Pair<String?, String?> {
        return prefs.getString("unit_number", null) to prefs.getString("medical_id", null)
    }
}
