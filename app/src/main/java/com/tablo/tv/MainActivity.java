package com.tablo.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native D-pad-first TV client. The companion tablo-web backend remains on the
 * local network and provides authentication, guide data, and HLS transcoding.
 */
public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "tablo_tv";
    private final ExecutorService network = Executors.newCachedThreadPool();
    private final List<Channel> channels = new ArrayList<>();
    private final List<StreamSlot> slots = new ArrayList<>();
    private SharedPreferences preferences;
    private LinearLayout root;
    private String baseUrl;
    private TextView status;
    private GridLayout channelGrid;
    private GridLayout streamGrid;
    private int maxStreams = 1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setNavigationBarColor(Color.rgb(16, 19, 26));
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        showLogin();
    }

    private void showLogin() {
        root = page();
        LinearLayout card = column(0);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView title = text("TABLO TV", 32, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        card.addView(title, margins(0, 20, 0, 8));
        TextView subtitle = text("Native live TV for Android TV and Fire TV", 16, color("tablo_muted"));
        subtitle.setGravity(Gravity.CENTER);
        card.addView(subtitle, margins(0, 0, 0, 28));

        EditText server = field("Backend address (for example http://192.168.1.20:7070)");
        server.setText(preferences.getString("server", "http://10.0.2.2:7070"));
        server.setSingleLine(true);
        card.addView(server, wideParams());

        EditText email = field("Tablo account email");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(email, wideParams());

        EditText password = field("Tablo account password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(password, wideParams());

        Button signIn = button("SIGN IN");
        card.addView(signIn, margins(0, 14, 0, 0));
        status = text("", 14, Color.rgb(255, 145, 145));
        status.setGravity(Gravity.CENTER);
        card.addView(status, margins(0, 12, 0, 0));

        signIn.setOnClickListener(v -> {
            String serverValue = server.getText().toString().trim();
            if (serverValue.endsWith("/")) serverValue = serverValue.substring(0, serverValue.length() - 1);
            if (serverValue.isEmpty() || email.getText().length() == 0 || password.getText().length() == 0) {
                status.setText("Enter the server address, email, and password.");
                return;
            }
            baseUrl = serverValue;
            setBusy(signIn, true, "CONNECTING…");
            network.execute(() -> {
                try {
                    request("/api/auth/login", "POST",
                            new JSONObject().put("email", email.getText().toString().trim())
                                    .put("password", password.getText().toString()).toString());
                    preferences.edit().putString("server", baseUrl).apply();
                    loadGuide();
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        setBusy(signIn, false, "SIGN IN");
                        status.setText(message(e));
                    });
                }
            });
        });
        server.requestFocus();
    }

    private void loadGuide() {
        network.execute(() -> {
            try {
                JSONArray data = new JSONArray(request("/api/channels/guide", "GET", null));
                channels.clear();
                for (int i = 0; i < data.length(); i++) channels.add(Channel.from(data.getJSONObject(i)));
                runOnUiThread(this::showDashboard);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (status != null) status.setText(message(e));
                });
            }
        });
    }

    private void showDashboard() {
        root = page();
        LinearLayout header = row(0);
        TextView title = text("TABLO TV", 25, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = text("STREAMS", 13, color("tablo_muted"));
        header.addView(count, margins(8, 0, 8, 0));
        Spinner streamCount = new Spinner(this);
        streamCount.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1 stream", "2 streams", "3 streams", "4 streams"}));
        streamCount.setSelection(maxStreams - 1);
        streamCount.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                setMaxStreams(position + 1);
                renderStreams();
            }
        });
        header.addView(streamCount, margins(0, 0, 18, 0));
        Button logout = button("LOG OUT");
        logout.setOnClickListener(v -> {
            stopAll();
            network.execute(() -> {
                try { request("/api/auth/logout", "DELETE", null); } catch (Exception ignored) {}
            });
            showLogin();
        });
        header.addView(logout);
        root.addView(header, wideParams());

        status = text("Select a channel to add it to the stream wall.", 14, color("tablo_muted"));
        root.addView(status, margins(24, 5, 24, 8));

        streamGrid = new GridLayout(this);
        streamGrid.setColumnCount(2);
        streamGrid.setRowCount(2);
        root.addView(streamGrid, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView guideTitle = text("LIVE GUIDE", 18, Color.WHITE);
        root.addView(guideTitle, margins(24, 15, 24, 8));
        ScrollView guideScroll = new ScrollView(this);
        channelGrid = new GridLayout(this);
        channelGrid.setColumnCount(4);
        guideScroll.addView(channelGrid);
        root.addView(guideScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderStreams();
        renderChannels();
    }

    private void renderChannels() {
        channelGrid.removeAllViews();
        for (Channel channel : channels) {
            Button item = button(channel.displayName + "\n" + channel.callSign);
            item.setTextSize(14);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setOnClickListener(v -> addStream(channel));
            channelGrid.addView(item, new GridLayout.LayoutParams(
                    new android.view.ViewGroup.LayoutParams(0, dp(62))));
            GridLayout.LayoutParams params = (GridLayout.LayoutParams) item.getLayoutParams();
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(6), dp(4), dp(6), dp(4));
            item.setLayoutParams(params);
        }
    }

    private void addStream(Channel channel) {
        for (StreamSlot slot : slots) {
            if (slot.channel.identifier.equals(channel.identifier)) {
                status.setText(channel.displayName + " is already playing.");
                return;
            }
        }
        if (slots.size() >= maxStreams) {
            status.setText("The " + maxStreams + "-stream wall is full. Change STREAMS or clear a tile.");
            return;
        }
        StreamSlot slot = new StreamSlot(channel);
        slots.add(slot);
        renderStreams();
        status.setText("Starting " + channel.displayName + "…");
        network.execute(() -> {
            try {
                JSONObject response = new JSONObject(request("/api/stream/" + Uri.encode(channel.identifier), "POST", null));
                slot.sessionId = response.getString("session_id");
                String streamUrl = response.getString("stream_url");
                if (!streamUrl.startsWith("http")) streamUrl = baseUrl + (streamUrl.startsWith("/") ? "" : "/") + streamUrl;
                String finalStreamUrl = streamUrl;
                runOnUiThread(() -> startPlayer(slot, finalStreamUrl));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    slots.remove(slot);
                    renderStreams();
                    status.setText("Could not start " + channel.displayName + ": " + message(e));
                });
            }
        });
    }

    private void setMaxStreams(int newMax) {
        maxStreams = newMax;
        while (slots.size() > maxStreams) {
            stopStream(slots.remove(slots.size() - 1));
        }
    }

    private void startPlayer(StreamSlot slot, String url) {
        slot.player = new ExoPlayer.Builder(this).build();
        slot.player.setMediaItem(MediaItem.fromUri(url));
        slot.player.prepare();
        slot.player.play();
        slot.playerView.setPlayer(slot.player);
        status.setText("Playing " + slot.channel.displayName + ".");
    }

    private void renderStreams() {
        if (streamGrid == null) return;
        streamGrid.removeAllViews();
        for (StreamSlot slot : slots) {
            LinearLayout tile = column(0);
            tile.setBackgroundColor(Color.BLACK);
            slot.playerView = new PlayerView(this);
            slot.playerView.setUseController(true);
            slot.playerView.setFocusable(true);
            tile.addView(slot.playerView, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView label = text(slot.channel.major + "-" + slot.channel.minor + "  " + slot.channel.displayName, 13, Color.WHITE);
            label.setPadding(dp(10), dp(4), dp(10), dp(4));
            tile.addView(label);
            tile.setOnClickListener(v -> removeStream(slot));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            streamGrid.addView(tile, params);
        }
        int empty = maxStreams - slots.size();
        for (int i = 0; i < empty; i++) {
            TextView placeholder = text("Choose a channel\nfor stream " + (slots.size() + i + 1), 16, color("tablo_muted"));
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackgroundColor(Color.rgb(27, 32, 43));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            streamGrid.addView(placeholder, params);
        }
    }

    private void removeStream(StreamSlot slot) {
        stopStream(slot);
        slots.remove(slot);
        renderStreams();
        status.setText("Stream cleared. Select another channel.");
    }

    private void stopAll() {
        for (StreamSlot slot : new ArrayList<>(slots)) stopStream(slot);
        slots.clear();
    }

    private void stopStream(StreamSlot slot) {
        if (slot.player != null) {
            slot.player.release();
            slot.player = null;
        }
        if (slot.sessionId != null) {
            String session = slot.sessionId;
            network.execute(() -> {
                try { request("/api/stream/" + Uri.encode(session), "DELETE", null); } catch (Exception ignored) {}
            });
        }
    }

    private String request(String path, String method, @Nullable String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Content-Type", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("Server returned " + code);
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private LinearLayout page() {
        LinearLayout layout = column(0);
        layout.setBackgroundColor(Color.rgb(16, 19, 26));
        layout.setPadding(dp(28), dp(20), dp(28), dp(18));
        setContentView(layout);
        return layout;
    }

    private LinearLayout row(int spacing) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(spacing, spacing, spacing, spacing);
        return layout;
    }

    private LinearLayout column(int spacing) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(spacing, spacing, spacing, spacing);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private EditText field(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(color("tablo_muted"));
        field.setTextColor(Color.WHITE);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(16), 0, dp(16), 0);
        return field;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setFocusable(true);
        return button;
    }

    private void setBusy(Button button, boolean busy, String label) {
        button.setEnabled(!busy);
        button.setText(label);
    }

    private LinearLayout.LayoutParams wideParams() {
        return new LinearLayout.LayoutParams(dp(600), dp(54));
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(String name) {
        return getResources().getColor(getResources().getIdentifier(name, "color", getPackageName()));
    }

    private String message(Exception e) {
        return e.getMessage() == null ? "Request failed." : e.getMessage();
    }

    @Override
    protected void onDestroy() {
        stopAll();
        network.shutdownNow();
        super.onDestroy();
    }

    private static class Channel {
        String identifier;
        String callSign;
        String displayName;
        int major;
        int minor;
        String kind;

        static Channel from(JSONObject object) throws Exception {
            Channel channel = new Channel();
            channel.identifier = object.getString("identifier");
            channel.callSign = object.optString("call_sign", "");
            channel.displayName = object.optString("display_name", channel.callSign);
            channel.major = object.optInt("major", 0);
            channel.minor = object.optInt("minor", 0);
            channel.kind = object.optString("kind", "ota");
            return channel;
        }
    }

    private static class StreamSlot {
        final Channel channel;
        String sessionId;
        ExoPlayer player;
        PlayerView playerView;

        StreamSlot(Channel channel) {
            this.channel = channel;
        }
    }
}
