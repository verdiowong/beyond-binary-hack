package com.example.emergencyresponse.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

/**
 * Result from a high-accuracy location request, containing both the
 * geocoded address string and the raw coordinates.
 */
data class ResolvedLocation(
    val address: String,
    val latitude: Double,
    val longitude: Double
)

class LocationHandler(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null

    /**
     * Check whether GPS or network location provider is enabled on the device.
     */
    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Request a high-accuracy location fix. Returns the geocoded address
     * and raw coordinates via [onLocationReady].
     */
    fun requestHighAccuracyLocation(
        onLocationReady: (ResolvedLocation) -> Unit,
        onError: (String) -> Unit
    ) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Missing ACCESS_FINE_LOCATION permission.")
            return
        }

        if (!isLocationEnabled()) {
            onError("Location services are disabled. Please enable GPS.")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMaxUpdateDelayMillis(3_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                val geocoder = Geocoder(context, Locale("en", "SG"))
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val formattedAddress = addresses?.firstOrNull()?.getAddressLine(0)
                    ?: "${location.latitude}, ${location.longitude}"

                onLocationReady(
                    ResolvedLocation(
                        address = formattedAddress,
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                )
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
}
