package ru.mycargobusiness.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class ApiClient {
    private static final int TIMEOUT_MS = 20_000;

    JSONObject testServices(String dadataKey, String orsKey) {
        JSONObject result = new JSONObject();
        try {
            ResolvedAddress address = resolveAddress("Москва, Снежная улица, 3А", dadataKey);
            boolean orsOk = testOrs(orsKey);
            result.put("ok", address != null && orsOk);
            result.put("message", address != null && orsOk
                    ? "DaData и openrouteservice подключены"
                    : "Один из сервисов не подтвердил подключение");
        } catch (Exception error) {
            try {
                result.put("ok", false);
                result.put("message", "Проверка не выполнена: " + safeMessage(error));
            } catch (Exception ignored) { }
        }
        return result;
    }

    JSONObject optimize(String requestJson, String dadataKey, String orsKey) {
        JSONObject result = new JSONObject();
        try {
            JSONObject request = new JSONObject(requestJson);
            ResolvedAddress start = resolveAddress(request.getString("loading"), dadataKey);
            if (start == null) throw new IllegalArgumentException("не найден адрес загрузки");

            JSONArray requestedStops = request.getJSONArray("stops");
            Map<Integer, ResolvedAddress> resolved = new HashMap<>();
            JSONArray jobs = new JSONArray();
            for (int i = 0; i < requestedStops.length(); i++) {
                JSONObject stop = requestedStops.getJSONObject(i);
                int originalIndex = stop.getInt("index");
                ResolvedAddress address = resolveAddress(stop.getString("address"), dadataKey);
                if (address == null) throw new IllegalArgumentException("не найден адрес: " + stop.getString("address"));
                resolved.put(originalIndex, address);
                jobs.put(new JSONObject()
                        .put("id", originalIndex + 1)
                        .put("service", 420)
                        .put("location", new JSONArray().put(address.lon).put(address.lat)));
            }

            JSONObject body = new JSONObject()
                    .put("jobs", jobs)
                    .put("vehicles", new JSONArray().put(new JSONObject()
                            .put("id", 1)
                            .put("profile", "driving-car")
                            .put("start", new JSONArray().put(start.lon).put(start.lat))));

            JSONObject optimized = requestJson(
                    "https://api.openrouteservice.org/optimization",
                    "POST",
                    orsKey,
                    body);
            JSONObject route = optimized.getJSONArray("routes").getJSONObject(0);
            JSONArray steps = route.getJSONArray("steps");
            JSONArray order = new JSONArray();
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.getJSONObject(i);
                if (!"job".equals(step.optString("type"))) continue;
                int originalIndex = step.getInt("id") - 1;
                ResolvedAddress address = resolved.get(originalIndex);
                order.put(new JSONObject()
                        .put("index", originalIndex)
                        .put("normalized", address.normalized)
                        .put("accuracy", accuracyText(address.quality)));
            }
            result.put("ok", true);
            result.put("order", order);
            result.put("distanceKm", Math.round(route.optDouble("distance", 0) / 100.0) / 10.0);
            result.put("durationHours", Math.round(route.optDouble("duration", 0) / 360.0) / 10.0);
        } catch (Exception error) {
            try {
                result.put("ok", false);
                result.put("message", "Не удалось рассчитать: " + safeMessage(error));
            } catch (Exception ignored) { }
        }
        return result;
    }

    private boolean testOrs(String key) throws Exception {
        String query = URLEncoder.encode("Москва", StandardCharsets.UTF_8.name());
        JSONObject json = requestJson(
                "https://api.openrouteservice.org/geocode/search?text=" + query + "&size=1",
                "GET",
                key,
                null);
        return json.optJSONArray("features") != null && json.getJSONArray("features").length() > 0;
    }

    private ResolvedAddress resolveAddress(String query, String token) throws Exception {
        JSONObject body = new JSONObject().put("query", query).put("count", 1);
        JSONObject json = requestJson(
                "https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/address",
                "POST",
                "Token " + token,
                body);
        JSONArray suggestions = json.optJSONArray("suggestions");
        if (suggestions == null || suggestions.length() == 0) return null;
        JSONObject suggestion = suggestions.getJSONObject(0);
        JSONObject data = suggestion.getJSONObject("data");
        String lat = data.optString("geo_lat", "");
        String lon = data.optString("geo_lon", "");
        if (lat.isEmpty() || lon.isEmpty()) return null;
        return new ResolvedAddress(
                suggestion.optString("unrestricted_value", suggestion.optString("value", query)),
                Double.parseDouble(lat),
                Double.parseDouble(lon),
                data.optInt("qc_geo", 5));
    }

    private JSONObject requestJson(String endpoint, String method, String authorization, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", authorization);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
        return new JSONObject(text.toString());
    }

    private String accuracyText(int quality) {
        if (quality == 0) return "точный дом";
        if (quality == 1) return "ближайший дом";
        if (quality == 2) return "найдена улица — проверьте";
        return "адрес неточный — проверьте";
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "неизвестная ошибка";
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static final class ResolvedAddress {
        final String normalized;
        final double lat;
        final double lon;
        final int quality;

        ResolvedAddress(String normalized, double lat, double lon, int quality) {
            this.normalized = normalized;
            this.lat = lat;
            this.lon = lon;
            this.quality = quality;
        }
    }
}
