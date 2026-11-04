package com.ansub.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

final class WeatherCard extends LinearLayout {
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=59.3293&longitude=18.0686&current=temperature_2m,weather_code";
    private static final long REFRESH_MS = 15 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private final TextView temperature, condition, credit;
    private long nextRefresh;
    private boolean active, fetching, closed;

    WeatherCard(Context context, float scale, Typeface regular, Typeface semibold) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(Math.round(18 * scale), Math.round(9 * scale),
                Math.round(18 * scale), Math.round(9 * scale));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x99101722);
        glass.setCornerRadius(18 * scale);
        glass.setStroke(Math.max(1, Math.round(scale)), 0x55FFFFFF);
        setBackground(glass);

        addView(label(17 * scale, 0xE6FFFFFF, regular, "Stockholm"));
        LinearLayout reading = new LinearLayout(context);
        reading.setGravity(Gravity.CENTER_VERTICAL);
        addView(reading, new LayoutParams(-1, -2));
        temperature = label(31 * scale, Color.WHITE, semibold, "—");
        reading.addView(temperature);
        condition = label(19 * scale, 0xF0FFFFFF, regular, "Loading weather");
        LayoutParams conditionPosition = new LayoutParams(0, -2, 1);
        conditionPosition.leftMargin = Math.round(12 * scale);
        reading.addView(condition, conditionPosition);
        credit = label(14 * scale, 0xCFFFFFFF, regular, "Open-Meteo");
        addView(credit);
    }

    private TextView label(float size, int color, Typeface font, String value) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        view.setTextColor(color);
        view.setTypeface(font);
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setText(value);
        return view;
    }

    void start() {
        active = true;
        schedule();
    }

    void stop() {
        active = false;
        main.removeCallbacks(refresh);
    }

    void close() {
        stop();
        closed = true;
        worker.shutdownNow();
    }

    private void schedule() {
        main.removeCallbacks(refresh);
        if (active && !fetching) main.postDelayed(refresh, Math.max(0, nextRefresh - SystemClock.elapsedRealtime()));
    }

    private void refresh() {
        fetching = true;
        worker.execute(() -> {
            try {
                JSONObject weather = fetchCurrent();
                double degrees = weather.getDouble("temperature_2m");
                if (Double.isNaN(degrees) || Double.isInfinite(degrees)) {
                    throw new IllegalArgumentException("Invalid temperature");
                }
                String reading = Math.round(degrees) + " °C";
                String description = describe(weather.getInt("weather_code"));
                main.post(() -> finish(reading, description));
            } catch (Exception error) {
                main.post(() -> finish(null, null));
            }
        });
    }

    private void finish(String reading, String description) {
        if (closed) return;
        fetching = false;
        nextRefresh = SystemClock.elapsedRealtime() + (reading == null ? RETRY_MS : REFRESH_MS);
        if (reading != null) {
            temperature.setText(reading);
            condition.setText(description);
            credit.setText("Open-Meteo");
        } else if ("—".contentEquals(temperature.getText())) {
            condition.setText("Weather unavailable");
        } else {
            credit.setText("Last update · Open-Meteo");
        }
        schedule();
    }

    private static JSONObject fetchCurrent() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(FORECAST).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            for (String line; (line = input.readLine()) != null; ) json.append(line);
            return new JSONObject(json.toString()).getJSONObject("current");
        } finally {
            connection.disconnect();
        }
    }

    private static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: case 65: return "Rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Rain showers";
            case 85: case 86: return "Snow showers";
            case 95: case 96: case 99: return "Thunderstorms";
            default: return "Current weather";
        }
    }
}
