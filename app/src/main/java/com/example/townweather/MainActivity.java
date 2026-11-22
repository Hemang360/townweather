package com.example.townweather;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.Callable;

public class MainActivity extends Activity {

    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        output = findViewById(R.id.output);
        EditText townInput = findViewById(R.id.townInput);

        findViewById(R.id.searchButton).setOnClickListener(v -> searchTown(townInput.getText().toString().trim()));
        findViewById(R.id.locationButton).setOnClickListener(v -> useMyLocation());
        useMyLocation();
    }

    // ---- Town search: Open-Meteo geocoding (name -> coordinates) ----
    private void searchTown(String town) {
        if (town.isEmpty()) return;
        output.setText("Searching…");
        inBackground(() -> {
            JSONObject res = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(town, "UTF-8"));
            JSONArray results = res.optJSONArray("results");
            if (results == null) throw new Exception("Town not found: " + town);
            JSONObject t = results.getJSONObject(0);
            String place = t.getString("name") + ", " + t.optString("admin1") + ", " + t.optString("country");
            return describe(t.getDouble("latitude"), t.getDouble("longitude"), place);
        });
    }

    // ---- Current location: GPS/network -> Geocoder (coordinates -> town name) ----
    @SuppressLint("MissingPermission") // checked at the top of the method
    private void useMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        output.setText("Getting location…");
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (last != null) {
            showLocation(last);
            return;
        }
        // No cached fix (common on emulators): wait for one.
        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
        if (!lm.isProviderEnabled(provider)) {
            output.setText("Location is off. Turn it on, or search a town.");
            return;
        }
        // ponytail: anonymous class, not a lambda — on Android < 11 these extra methods are abstract and a lambda would crash
        lm.requestSingleUpdate(provider, new LocationListener() {
            @Override public void onLocationChanged(Location l) { showLocation(l); }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        }, null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) useMyLocation();
        else output.setText("Location permission denied. Search a town instead.");
    }

    private void showLocation(Location l) {
        inBackground(() -> describe(l.getLatitude(), l.getLongitude(), townName(l.getLatitude(), l.getLongitude())));
    }

    private String townName(double lat, double lon) {
        try {
            List<Address> list = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
            if (list != null && !list.isEmpty()) {
                Address a = list.get(0);
                String town = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                return town + ", " + a.getAdminArea() + ", " + a.getCountryName();
            }
        } catch (Exception ignored) {
            // no geocoder backend / offline: fall through to "Your location"
        }
        return "Your location";
    }

    // ---- Weather: Open-Meteo forecast (coordinates -> current weather) ----
    private String describe(double lat, double lon, String place) throws Exception {
        JSONObject cur = getJson("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code")
                .getJSONObject("current");
        return place + "\n"
                + String.format(Locale.US, "(%.4f, %.4f)", lat, lon) + "\n\n"
                + weatherText(cur.getInt("weather_code")) + "\n"
                + "Temperature: " + cur.getDouble("temperature_2m") + " °C\n"
                + "Humidity: " + cur.getInt("relative_humidity_2m") + " %\n"
                + "Wind: " + cur.getDouble("wind_speed_10m") + " km/h";
    }

    // WMO weather codes, grouped: https://open-meteo.com/en/docs
    private static String weatherText(int c) {
        if (c == 0) return "Clear sky";
        if (c <= 3) return "Partly cloudy";
        if (c <= 48) return "Fog";
        if (c <= 57) return "Drizzle";
        if (c <= 67) return "Rain";
        if (c <= 77) return "Snow";
        if (c <= 82) return "Rain showers";
        if (c <= 86) return "Snow showers";
        return "Thunderstorm";
    }

    // ---- Helpers ----
    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try (Scanner s = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A")) {
            return new JSONObject(s.next());
        } finally {
            conn.disconnect();
        }
    }

    // Network must not run on the UI thread; run task, then show its text (or the error).
    private void inBackground(Callable<String> task) {
        new Thread(() -> {
            String text;
            try {
                text = task.call();
            } catch (Exception e) {
                text = "Error: " + e.getMessage();
            }
            String result = text;
            runOnUiThread(() -> output.setText(result));
        }).start();
    }
}
