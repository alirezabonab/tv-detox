package com.ansub.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

final class WeatherCard extends LinearLayout {
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=59.2947&longitude=18.0841&timezone=Europe%2FStockholm"
            + "&current=temperature_2m,weather_code,is_day"
            + "&hourly=temperature_2m,weather_code,is_day"
            + "&daily=temperature_2m_max,temperature_2m_min,weather_code&forecast_days=6&timeformat=unixtime";
    private static final int HOURS = 6, DAYS = 5;
    private static final int PRIMARY_TEXT = 0xD9FFFFFF;
    private static final int SECONDARY_TEXT = 0xBFFFFFFF;
    private static final long REFRESH_MS = 15 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private final TextView temperature, condition, highLow;
    private final ImageView currentIcon;
    private final ForecastRow hourly, daily;
    private long nextRefresh;
    private boolean active, fetching, closed;

    WeatherCard(Context context, float scale, Typeface regular, Typeface semibold) {
        super(context);
        setOrientation(VERTICAL);
        LinearLayout summary = new LinearLayout(context);
        summary.setGravity(Gravity.TOP);
        addView(summary, new LayoutParams(-1, -2));

        LinearLayout current = new LinearLayout(context);
        current.setOrientation(VERTICAL);
        summary.addView(current, new LayoutParams(0, -2, 1));
        current.addView(label(23 * scale, SECONDARY_TEXT, semibold, "Johanneshov"));
        temperature = label(65 * scale, PRIMARY_TEXT, regular, "—");
        current.addView(temperature);

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(VERTICAL);
        details.setGravity(Gravity.END);
        summary.addView(details, new LayoutParams(0, -2, 1.3f));
        currentIcon = addIcon(details, scale, 29, Gravity.END, 0);
        condition = label(21 * scale, PRIMARY_TEXT, semibold, "Loading weather");
        details.addView(condition, new LayoutParams(-2, -2));
        highLow = label(19 * scale, PRIMARY_TEXT, semibold, "");
        details.addView(highLow, new LayoutParams(-2, -2));

        hourly = new ForecastRow(HOURS, scale, semibold, 8, 21);
        daily = new ForecastRow(DAYS, scale, semibold, 16, 16);
    }

    private final class ForecastRow {
        final LinearLayout view;
        final TextView[] labels, temperatures;
        final ImageView[] icons;

        ForecastRow(int count, float scale, Typeface font, int topMargin, int temperatureSize) {
            Context context = getContext();
            view = new LinearLayout(context);
            view.setVisibility(GONE);
            LayoutParams rowPosition = new LayoutParams(-1, -2);
            rowPosition.topMargin = Math.round(topMargin * scale);
            addView(view, rowPosition);
            labels = new TextView[count];
            temperatures = new TextView[count];
            icons = new ImageView[count];
            for (int index = 0; index < count; index++) {
                LinearLayout column = new LinearLayout(context);
                column.setOrientation(VERTICAL);
                column.setGravity(Gravity.CENTER_HORIZONTAL);
                view.addView(column, new LayoutParams(0, -2, 1));
                labels[index] = label(18 * scale, SECONDARY_TEXT, font, "");
                column.addView(labels[index], new LayoutParams(-2, -2));
                icons[index] = addIcon(column, scale, 27, Gravity.CENTER_HORIZONTAL, 4);
                temperatures[index] = label(temperatureSize * scale, PRIMARY_TEXT, font, "");
                LayoutParams temperaturePosition = new LayoutParams(-2, -2);
                temperaturePosition.topMargin = Math.round(4 * scale);
                column.addView(temperatures[index], temperaturePosition);
            }
        }

        void set(int index, String label, CharSequence temperature, int code, boolean isDay) {
            labels[index].setText(label);
            temperatures[index].setText(temperature);
            showIcon(icons[index], code, isDay);
        }
    }

    private ImageView addIcon(LinearLayout parent, float scale, int size, int gravity, int margin) {
        ImageView icon = new ImageView(getContext());
        int pixels = Math.round(size * scale);
        LayoutParams position = new LayoutParams(pixels, pixels);
        position.gravity = gravity;
        position.topMargin = Math.round(margin * scale);
        parent.addView(icon, position);
        return icon;
    }

    private TextView label(float size, int color, Typeface font, String value) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        view.setTextColor(color);
        view.setShadowLayer(size * 0.07f, 0, size * 0.035f, 0xB3000000);
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
                JSONObject forecast = fetchForecast();
                main.post(() -> finish(forecast));
            } catch (Exception error) {
                main.post(() -> finish(null));
            }
        });
    }

    private void finish(JSONObject forecast) {
        if (closed) return;
        fetching = false;
        boolean loaded = false;
        if (forecast != null) {
            try {
                render(forecast);
                loaded = true;
            } catch (JSONException error) {
                // Keep the previous forecast when the response is incomplete.
            }
        }
        if (!loaded) condition.setText(hourly.view.getVisibility() == VISIBLE ? "Last available reading" : "Weather unavailable");
        nextRefresh = SystemClock.elapsedRealtime() + (loaded ? REFRESH_MS : RETRY_MS);
        schedule();
    }

    private void render(JSONObject forecast) throws JSONException {
        JSONObject current = forecast.getJSONObject("current");
        JSONObject daily = forecast.getJSONObject("daily");
        JSONObject hours = forecast.getJSONObject("hourly");
        long currentTime = current.getLong("time");
        JSONArray times = hours.getJSONArray("time");
        JSONArray hourlyTemperatures = hours.getJSONArray("temperature_2m");
        JSONArray codes = hours.getJSONArray("weather_code");
        JSONArray daylight = hours.getJSONArray("is_day");
        int first = 0;
        while (first < times.length() && times.getLong(first) <= currentTime) first++;
        if (times.length() - first < HOURS) throw new JSONException("Incomplete hourly forecast");

        String currentTemperature = degrees(current.getDouble("temperature_2m"));
        int currentCode = current.getInt("weather_code");
        boolean currentDaylight = current.getInt("is_day") == 1;
        JSONArray highs = daily.getJSONArray("temperature_2m_max");
        JSONArray lows = daily.getJSONArray("temperature_2m_min");
        JSONArray dailyTimes = daily.getJSONArray("time");
        JSONArray dailyCodes = daily.getJSONArray("weather_code");
        String high = degrees(highs.getDouble(0));
        String low = degrees(lows.getDouble(0));
        SimpleDateFormat localHour = new SimpleDateFormat("HH", Locale.ROOT);
        TimeZone stockholm = TimeZone.getTimeZone("Europe/Stockholm");
        localHour.setTimeZone(stockholm);
        SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.ENGLISH);
        weekday.setTimeZone(stockholm);

        String[] labels = new String[HOURS];
        String[] temperatures = new String[HOURS];
        int[] weatherCodes = new int[HOURS];
        boolean[] day = new boolean[HOURS];
        for (int index = 0; index < HOURS; index++) {
            int hour = first + index;
            labels[index] = localHour.format(new Date(times.getLong(hour) * 1000));
            temperatures[index] = degrees(hourlyTemperatures.getDouble(hour));
            weatherCodes[index] = codes.getInt(hour);
            day[index] = daylight.getInt(hour) == 1;
        }
        String[] dayLabels = new String[DAYS];
        CharSequence[] ranges = new CharSequence[DAYS];
        int[] dayCodes = new int[DAYS];
        for (int index = 0; index < DAYS; index++) {
            int forecastDay = index + 1;
            dayLabels[index] = weekday.format(new Date(dailyTimes.getLong(forecastDay) * 1000));
            String dayHigh = degrees(highs.getDouble(forecastDay));
            String dayLow = degrees(lows.getDouble(forecastDay));
            SpannableString range = new SpannableString(dayHigh + " " + dayLow);
            range.setSpan(new ForegroundColorSpan(SECONDARY_TEXT), dayHigh.length() + 1,
                    range.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ranges[index] = range;
            dayCodes[index] = dailyCodes.getInt(forecastDay);
        }
        temperature.setText(currentTemperature);
        condition.setText(describe(currentCode));
        highLow.setText("H:" + high + "  L:" + low);
        showIcon(currentIcon, currentCode, currentDaylight);
        for (int index = 0; index < HOURS; index++) {
            hourly.set(index, labels[index], temperatures[index], weatherCodes[index], day[index]);
        }
        for (int index = 0; index < DAYS; index++) {
            this.daily.set(index, dayLabels[index], ranges[index], dayCodes[index], true);
        }
        hourly.view.setVisibility(VISIBLE);
        this.daily.view.setVisibility(VISIBLE);
    }

    private static String degrees(double value) throws JSONException {
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new JSONException("Invalid temperature");
        return Math.round(value) + "°";
    }

    private void showIcon(ImageView view, int code, boolean day) {
        String name;
        if (code <= 1) name = day ? "sunny" : "night";
        else if (code == 2) name = day ? "partly_cloudy" : "night_partly_cloudy";
        else if (code == 3) name = "cloudy";
        else if (code == 45 || code == 48) name = "fog";
        else if (code >= 95) name = "lightning_rainy";
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) name = "snowy";
        else name = "rainy";
        view.setImageResource(getResources().getIdentifier("weather_" + name, "drawable", getContext().getPackageName()));
        view.setContentDescription(describe(code));
    }

    private static JSONObject fetchForecast() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(FORECAST).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            for (String line; (line = input.readLine()) != null; ) json.append(line);
            return new JSONObject(json.toString());
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
