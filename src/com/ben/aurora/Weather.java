package com.ben.aurora;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Weather via Open-Meteo (no API key). Uses the device's coarse location when the
 * permission is available, otherwise falls back to IP geolocation. All network work
 * happens on the caller's background thread.
 */
final class Weather {
    private Weather() {}

    static final class Data {
        float temp;
        int code;
    }

    static Data load(Context context, boolean useLocation) {
        double[] loc = null;
        if (useLocation && hasLocationPermission(context)) {
            loc = deviceLocation(context);
        }
        if (loc == null) {
            loc = ipLocation();
        }
        if (loc == null) return null;

        try {
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code&timezone=auto",
                    loc[0], loc[1]);
            JSONObject root = new JSONObject(http(url));
            JSONObject cur = root.optJSONObject("current");
            if (cur == null) return null;
            Data d = new Data();
            d.temp = (float) cur.optDouble("temperature_2m", Double.NaN);
            d.code = cur.optInt("weather_code", -1);
            if (Float.isNaN(d.temp) || d.code < 0) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean hasLocationPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static double[] deviceLocation(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;

            String[] providers = {LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER};
            for (String p : providers) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null) return new double[]{l.getLatitude(), l.getLongitude()};
                } catch (Throwable ignored) {
                }
            }

            // No cached fix: ask for one, but do not hang the worker for long.
            final CountDownLatch latch = new CountDownLatch(1);
            final double[][] out = new double[1][];
            CancellationSignal signal = new CancellationSignal();
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal,
                    c.getMainExecutor(), new java.util.function.Consumer<Location>() {
                        @Override
                        public void accept(Location location) {
                            if (location != null) {
                                out[0] = new double[]{location.getLatitude(),
                                        location.getLongitude()};
                            }
                            latch.countDown();
                        }
                    });
            if (latch.await(3, TimeUnit.SECONDS) && out[0] != null) {
                return out[0];
            }
            signal.cancel();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static double[] ipLocation() {
        double[] d = ipFrom("https://ipapi.co/json/", "latitude", "longitude");
        if (d != null) return d;
        return ipFrom("https://ipwho.is/", "latitude", "longitude");
    }

    private static double[] ipFrom(String url, String latKey, String lonKey) {
        try {
            JSONObject o = new JSONObject(http(url));
            double lat = o.optDouble(latKey, Double.NaN);
            double lon = o.optDouble(lonKey, Double.NaN);
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) return new double[]{lat, lon};
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String http(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(9000);
        conn.setReadTimeout(9000);
        conn.setRequestProperty("User-Agent", "Aurora-Launcher");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    static String emoji(int code) {
        if (code == 0) return "\u2600\uFE0F";
        if (code <= 2) return "\uD83C\uDF24\uFE0F";
        if (code == 3) return "\u2601\uFE0F";
        if (code == 45 || code == 48) return "\uD83C\uDF2B\uFE0F";
        if (code >= 51 && code <= 57) return "\uD83C\uDF26\uFE0F";
        if (code >= 61 && code <= 67) return "\uD83C\uDF27\uFE0F";
        if (code >= 71 && code <= 77) return "\u2744\uFE0F";
        if (code >= 80 && code <= 82) return "\uD83C\uDF26\uFE0F";
        if (code >= 85 && code <= 86) return "\uD83C\uDF28\uFE0F";
        if (code >= 95) return "\u26C8\uFE0F";
        return "\uD83C\uDF24\uFE0F";
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 55) return "Drizzle";
        if (code == 56 || code == 57) return "Freezing drizzle";
        if (code >= 61 && code <= 65) return "Rain";
        if (code == 66 || code == 67) return "Freezing rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code == 95) return "Thunderstorm";
        if (code >= 96) return "Thunderstorm, hail";
        return "Weather";
    }
}
