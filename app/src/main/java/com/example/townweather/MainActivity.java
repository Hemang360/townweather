package com.example.townweather;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class MainActivity extends Activity {

    // WMO weather codes grouped by upper bound: https://open-meteo.com/en/docs
    private static final int[] CODE_MAX = {0, 3, 48, 57, 67, 77, 82, 86, 99};
    private static final String[] EMOJI = {"☀️", "⛅", "🌫️", "🌦️", "🌧️", "❄️", "🌧️", "🌨️", "⛈️"};
    private static final String[] LABEL = {"Clear sky", "Partly cloudy", "Fog", "Drizzle", "Rain",
            "Snow", "Rain showers", "Snow showers", "Thunderstorm"};
    private static final int[][] SKY = {          // background gradient {top, bottom}
            {0xFF2F80ED, 0xFF56CCF2}, {0xFF4B79A1, 0xFF7FA7C9}, {0xFF757F9A, 0xFFA7B0BE},
            {0xFF4A6B8A, 0xFF7C93A8}, {0xFF314755, 0xFF5B7486}, {0xFF83A4D4, 0xFFB6C9E0},
            {0xFF314755, 0xFF5B7486}, {0xFF83A4D4, 0xFFB6C9E0}, {0xFF232526, 0xFF414345}};
    private static final int[] NIGHT = {0xFF0F2027, 0xFF2C5364};

    // Map types, cycled by the map button. All keyless.
    // (tile.openstreetmap.org blocks apps with "403 Access blocked"; CARTO now needs an API key.)
    // ponytail: Esri basemaps are fine for student/dev use; a published app should get a free ArcGIS/MapTiler key
    private static final ITileSource[] MAP_TYPES = {
            esri("EsriStreet", "World_Street_Map", "Tiles © Esri, HERE, Garmin, © OpenStreetMap contributors"),
            TileSourceFactory.OpenTopo,
            esri("EsriSatellite", "World_Imagery", "Tiles © Esri, Maxar, Earthstar Geographics")};
    private static final String[] MAP_NAMES = {"🗺 Street", "⛰ Terrain", "🛰 Satellite"};

    private TextView place, coords, icon, temp, condition, details;
    private MapView map;
    private Marker marker;
    private int mapType = 0;
    private double lastLat, lastLon;
    private String lastPlace;

    interface Task { void run() throws Exception; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // osmdroid setup must happen before the MapView is inflated; OSM tile servers require a user agent
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue("TownWeather/1.0 (Android student project)");
        setContentView(R.layout.activity_main);

        place = findViewById(R.id.place);
        coords = findViewById(R.id.coords);
        icon = findViewById(R.id.icon);
        temp = findViewById(R.id.temp);
        condition = findViewById(R.id.condition);
        details = findViewById(R.id.details);
        setSky(SKY[0]);

        EditText townInput = findViewById(R.id.townInput);
        findViewById(R.id.searchButton).setOnClickListener(v -> searchTown(townInput));
        townInput.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            searchTown(townInput);
            return true;
        });
        findViewById(R.id.locationButton).setOnClickListener(v -> useMyLocation());

        setupMap();
        useMyLocation();
    }

    // ---------------- Map ----------------

    private void setupMap() {
        map = findViewById(R.id.map);
        map.setTileSource(MAP_TYPES[0]);
        map.setMultiTouchControls(true);
        map.getOverlays().add(new CopyrightOverlay(this)); // map credit, required by the tile licence
        map.getController().setZoom(4.0);
        map.getController().setCenter(new GeoPoint(20.0, 78.0));

        marker = new Marker(map);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

        // Tap anywhere on the map -> weather for that spot
        map.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) {
                status("Loading…");
                runAsync(() -> fetchWeather(p.getLatitude(), p.getLongitude(), null));
                return true;
            }
            @Override public boolean longPressHelper(GeoPoint p) { return false; }
        }));

        Button mapType = findViewById(R.id.mapTypeButton);
        mapType.setText(MAP_NAMES[0]);
        mapType.setOnClickListener(v -> {
            this.mapType = (this.mapType + 1) % MAP_TYPES.length;
            map.setTileSource(MAP_TYPES[this.mapType]);
            mapType.setText(MAP_NAMES[this.mapType]);
        });

        findViewById(R.id.openMapsButton).setOnClickListener(v -> {
            if (lastPlace == null) { toast("Search a place first"); return; }
            Uri geo = Uri.parse("geo:" + lastLat + "," + lastLon + "?q=" + lastLat + "," + lastLon
                    + "(" + Uri.encode(lastPlace) + ")");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, geo));
            } catch (Exception e) {
                toast("No maps app installed");
            }
        });
    }

    @Override protected void onResume() { super.onResume(); map.onResume(); }
    @Override protected void onPause() { super.onPause(); map.onPause(); }

    // ---------------- Search: town name -> coordinates (Open-Meteo geocoding) ----------------

    private void searchTown(EditText input) {
        String town = input.getText().toString().trim();
        if (town.isEmpty()) { toast("Type a town name"); return; }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        status("Searching “" + town + "”…");
        runAsync(() -> {
            JSONObject res = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(town, "UTF-8"));
            JSONArray results = res.optJSONArray("results");
            if (results == null) throw new Exception("No town called “" + town + "” found");
            JSONObject t = results.getJSONObject(0);
            String name = t.getString("name");
            if (!t.optString("admin1").isEmpty()) name += ", " + t.optString("admin1");
            if (!t.optString("country").isEmpty()) name += ", " + t.optString("country");
            fetchWeather(t.getDouble("latitude"), t.getDouble("longitude"), name);
        });
    }

    // ---------------- My location: GPS -> coordinates ----------------

    @SuppressLint("MissingPermission") // checked at the top of the method
    private void useMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        status("Finding your location…");
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (last != null) {
            onLocation(last);
            return;
        }
        // No cached fix (common on emulators): wait for one.
        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
        if (!lm.isProviderEnabled(provider)) {
            status("Location is off. Turn it on, or search a town.");
            return;
        }
        // ponytail: anonymous class, not a lambda — on Android < 11 these extra methods are abstract and a lambda would crash
        lm.requestSingleUpdate(provider, new LocationListener() {
            @Override public void onLocationChanged(Location l) { onLocation(l); }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        }, null);
    }

    private void onLocation(Location l) {
        runAsync(() -> fetchWeather(l.getLatitude(), l.getLongitude(), null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) useMyLocation();
        else status("Location permission denied. Search a town instead.");
    }

    // ---------------- Weather: coordinates -> current weather (Open-Meteo) ----------------

    /** Runs on a background thread. name == null means "work out the town name from the coordinates". */
    private void fetchWeather(double lat, double lon, String name) throws Exception {
        String placeName = name != null ? name : townName(lat, lon);
        JSONObject w = getJson("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1");
        JSONObject cur = w.getJSONObject("current");
        JSONObject daily = w.getJSONObject("daily");

        int kind = kind(cur.getInt("weather_code"));
        boolean night = cur.optInt("is_day", 1) == 0;
        String emoji = night && kind == 0 ? "🌙" : EMOJI[kind];
        String tempText = Math.round(cur.getDouble("temperature_2m")) + "°C";
        String detailText = "Feels like " + Math.round(cur.getDouble("apparent_temperature")) + "°C\n"
                + "High " + Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)) + "°  ·  "
                + "Low " + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)) + "°\n"
                + "💧 Humidity " + cur.getInt("relative_humidity_2m") + "%   "
                + "💨 Wind " + cur.getDouble("wind_speed_10m") + " km/h";

        runOnUiThread(() -> {
            lastLat = lat;
            lastLon = lon;
            lastPlace = placeName;
            place.setText(placeName);
            coords.setText(String.format(Locale.US, "%.4f, %.4f", lat, lon));
            icon.setText(emoji);
            temp.setText(tempText);
            condition.setText(LABEL[kind] + (night ? " · Night" : ""));
            details.setText(detailText);
            setSky(night ? NIGHT : SKY[kind]);

            GeoPoint point = new GeoPoint(lat, lon);
            marker.setPosition(point);
            marker.setTitle(placeName + "\n" + tempText + ", " + LABEL[kind]);
            if (!map.getOverlays().contains(marker)) map.getOverlays().add(marker);
            map.getController().animateTo(point, 11.0, 800L);
            map.invalidate();
        });
    }

    private String townName(double lat, double lon) {
        try {
            List<Address> list = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
            if (list != null && !list.isEmpty()) {
                Address a = list.get(0);
                String town = a.getLocality() != null ? a.getLocality()
                        : a.getSubAdminArea() != null ? a.getSubAdminArea() : a.getAdminArea();
                if (town == null) return a.getCountryName() != null ? a.getCountryName() : "Unknown place";
                return a.getCountryName() != null ? town + ", " + a.getCountryName() : town;
            }
        } catch (Exception ignored) {
            // no geocoder backend / offline: fall through
        }
        return "Selected location";
    }

    // Esri tile URLs are .../tile/{z}/{y}/{x} (y before x), so build the URL ourselves.
    private static ITileSource esri(String name, String service, String credit) {
        return new OnlineTileSourceBase(name, 0, 19, 256, "",
                new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/" + service + "/MapServer/tile/"}, credit) {
            @Override public String getTileURLString(long tile) {
                return getBaseUrl() + MapTileIndex.getZoom(tile) + "/" + MapTileIndex.getY(tile) + "/" + MapTileIndex.getX(tile);
            }
        };
    }

    private static int kind(int code) {
        for (int i = 0; i < CODE_MAX.length; i++) if (code <= CODE_MAX[i]) return i;
        return CODE_MAX.length - 1;
    }

    // ---------------- Helpers ----------------

    private void setSky(int[] colors) {
        findViewById(R.id.root).setBackground(
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors));
        getWindow().setStatusBarColor(colors[0]);
    }

    private void status(String msg) {
        coords.setText(msg);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

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

    // Network must not run on the UI thread. Errors are shown in the card.
    private void runAsync(Task task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> status("⚠ " + msg));
            }
        }).start();
    }
}
