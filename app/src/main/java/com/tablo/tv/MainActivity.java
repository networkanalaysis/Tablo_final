package com.tablo.tv;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
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
    private String tabloBaseUrl;
    private volatile String discoveredTabloUrl;
    private TextView status;
    private GridLayout channelGrid;
    private GridLayout streamGrid;
    private String channelFilter = "";
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

        TextView title = text("TABLO", 32, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        card.addView(title, margins(0, 20, 0, 8));
        TextView subtitle = text("Sign in with your Tablo account", 16, color("tablo_muted"));
        subtitle.setGravity(Gravity.CENTER);
        card.addView(subtitle, margins(0, 0, 0, 28));

        EditText email = field("Tablo account email");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(editableRow(email), wideParams());

        EditText password = field("Tablo account password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(editableRow(password), wideParams());

        Button signIn = button("SIGN IN");
        signIn.setBackgroundResource(R.drawable.button_accent);
        card.addView(signIn, margins(0, 14, 0, 0));
        status = text("", 14, Color.rgb(255, 145, 145));
        status.setGravity(Gravity.CENTER);
        card.addView(status, margins(0, 12, 0, 0));

        signIn.setOnClickListener(v -> {
            if (email.getText().length() == 0 || password.getText().length() == 0) {
                status.setText("Enter your Tablo email and password.");
                return;
            }
            setBusy(signIn, true, "FINDING TABLO…");
            status.setText("Looking for Tablo on your local network…");
            network.execute(() -> {
                try {
                    tabloBaseUrl = discoveredTabloUrl != null ? discoveredTabloUrl : discoverTablo();
                    runOnUiThread(() -> status.setText("Connecting to your Tablo…"));
                    loadGuide();
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        setBusy(signIn, false, "SIGN IN");
                        status.setText(message(e));
                    });
                }
            });
        });
        email.requestFocus();
        status.setText("Searching your local network for Tablo…");
        network.execute(() -> {
            try {
                discoveredTabloUrl = discoverTablo();
                runOnUiThread(() -> {
                    if (status != null) status.setText("Tablo found. Enter your credentials to continue.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (status != null) status.setText("Tablo not found yet. Keep this TV on the same network and press SIGN IN to retry.");
                });
            }
        });
    }

    private LinearLayout editableRow(EditText editor) {
        LinearLayout row = row(0);
        row.setBackgroundColor(Color.rgb(27, 32, 43));
        row.addView(editor, new LinearLayout.LayoutParams(0, -1, 1));
        Button paste = button("PASTE");
        paste.setOnClickListener(v -> pasteInto(editor));
        row.addView(paste, margins(0, 0, 4, 0));
        Button copy = button("COPY");
        copy.setOnClickListener(v -> copyFrom(editor));
        row.addView(copy);
        return row;
    }

    private void pasteInto(EditText editor) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                editor.setText(clip.getItemAt(0).coerceToText(this));
                editor.setSelection(editor.length());
            }
        } else {
            Toast.makeText(this, "Nothing to paste", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFrom(EditText editor) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Tablo", editor.getText()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private String discoverTablo() throws Exception {
        List<String> discoveredIps = new ArrayList<>();
        try (DatagramSocket receiveSocket = new DatagramSocket(8882);
             DatagramSocket sendSocket = new DatagramSocket()) {
            receiveSocket.setSoTimeout(350);
            receiveSocket.setBroadcast(true);
            byte[] message = "tablo-discover".getBytes(StandardCharsets.UTF_8);
            sendSocket.send(new DatagramPacket(message, message.length,
                    InetAddress.getByName("255.255.255.255"), 8881));
            long deadline = System.currentTimeMillis() + 2500;
            byte[] buffer = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    receiveSocket.receive(packet);
                    String ip = packet.getAddress().getHostAddress();
                    if (!discoveredIps.contains(ip)) discoveredIps.add(ip);
                } catch (SocketTimeoutException ignored) {
                    // Continue until the discovery window closes.
                }
            }
        } catch (Exception ignored) {
            // Some TV networks block broadcast; association-server discovery is
            // still attempted below.
        }

        String association = rawRequest(
                "https://api.tablotv.com/assocserver/getipinfo/", "GET", null);
        JSONObject associationJson = new JSONObject(association);
        JSONArray cpes = associationJson.optJSONArray("cpes");
        if (cpes != null) {
            for (int i = 0; i < cpes.length(); i++) {
                JSONObject cpe = cpes.getJSONObject(i);
                String ip = cpe.optString("private_ip", cpe.optString("slip", ""));
                if (!ip.isEmpty() && !discoveredIps.contains(ip)) discoveredIps.add(ip);
            }
        }

        for (String ip : discoveredIps) {
            String candidate = "http://" + ip + ":8885";
            try {
                String info = rawRequest(candidate + "/server/info", "GET", null);
                if (new JSONObject(info).length() > 0) {
                    preferences.edit().putString("tablo_host", candidate).apply();
                    return candidate;
                }
            } catch (Exception ignored) {
                // A stale association record or unrelated UDP response.
            }
        }

        // Fall back to a bounded local subnet scan when UDP and association
        // lookup do not return a usable address.
        List<String> prefixes = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
            Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                java.net.InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    String host = address.getHostAddress();
                    int lastDot = host.lastIndexOf('.');
                    if (lastDot > 0) prefixes.add(host.substring(0, lastDot));
                }
            }
        }
        if (prefixes.isEmpty()) throw new Exception("No Tablo network found. Connect the TV to Wi-Fi and try again.");

        List<java.util.concurrent.Future<String>> probes = new ArrayList<>();
        for (String prefix : prefixes) {
            for (int host = 1; host < 255; host++) {
                final String candidate = "http://" + prefix + "." + host + ":8885";
                probes.add(network.submit(() -> {
                    try {
                        String response = rawRequest(candidate + "/server/info", "GET", null);
                        return new JSONObject(response).length() > 0 ? candidate : null;
                    } catch (Exception ignored) {
                        return null;
                    }
                }));
            }
        }
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (java.util.concurrent.Future<String> probe : probes) {
                if (probe.isDone()) {
                    String result = probe.get();
                    if (result != null) {
                        preferences.edit().putString("tablo_host", result).apply();
                        return result;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new Exception("No Tablo was found on this network. Connect the TV to the same Wi-Fi as your Tablo and try again.");
    }

    private void loadGuide() {
        network.execute(() -> {
            try {
                channels.clear();
                JSONArray paths = new JSONArray(request("/guide/channels", "GET", null));
                for (int i = 0; i < paths.length(); i++) {
                    String path = paths.optString(i, "");
                    if (!path.isEmpty()) {
                        channels.add(Channel.fromTablo(path,
                                new JSONObject(request(path, "GET", null))));
                    }
                }
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
        TextView title = text("TABLO", 25, Color.WHITE);
        header.addView(title, margins(0, 0, 24, 0));
        Button liveTab = button("Live TV");
        liveTab.setTextColor(color("tablo_accent"));
        header.addView(liveTab, margins(0, 0, 4, 0));
        Button guideTab = button("Guide");
        guideTab.setOnClickListener(v -> status.setText("Guide view is loading from the Tablo service."));
        header.addView(guideTab, margins(0, 0, 4, 0));
        Button libraryTab = button("Library");
        libraryTab.setOnClickListener(v -> status.setText("Library view is loading from the Tablo service."));
        header.addView(libraryTab, new LinearLayout.LayoutParams(0, -2, 1));
        EditText search = field("Search programs, channels…");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                channelFilter = s.toString().trim().toLowerCase();
                renderChannels();
            }
            public void afterTextChanged(Editable s) {}
        });
        header.addView(search, new LinearLayout.LayoutParams(dp(300), dp(48)));
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

        TextView onAir = text("ON AIR NOW", 28, Color.WHITE);
        root.addView(onAir, margins(24, 8, 24, 0));
        TextView onAirSubtitle = text("Browse your local guide and start watching instantly", 14, color("tablo_muted"));
        root.addView(onAirSubtitle, margins(24, 0, 24, 10));

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
            String searchable = (channel.callSign + " " + channel.displayName).toLowerCase();
            if (!channelFilter.isEmpty() && !searchable.contains(channelFilter)) continue;
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
                JSONObject response = new JSONObject(request(channel.identifier + "/watch", "POST", "{}"));
                slot.sessionId = response.optString("token", "");
                String streamUrl = response.getString("playlist_url");
                if (!streamUrl.startsWith("http")) {
                    streamUrl = tabloBaseUrl + (streamUrl.startsWith("/") ? "" : "/") + streamUrl;
                }
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
                try { request("/watch/" + Uri.encode(session), "DELETE", null); } catch (Exception ignored) {}
            });
        }
    }

    private String request(String path, String method, @Nullable String body) throws Exception {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return rawRequest(tabloBaseUrl + normalized, method, body);
    }

    private String rawRequest(String target, String method, @Nullable String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
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
        field.setBackgroundResource(R.drawable.field_surface);
        return field;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackgroundResource(R.drawable.button_surface);
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

        static Channel fromTablo(String path, JSONObject object) {
            Channel channel = new Channel();
            JSONObject metadata = object.optJSONObject("channel");
            if (metadata == null) metadata = object;
            channel.identifier = path;
            channel.callSign = metadata.optString("call_sign", metadata.optString("network", "TABLO"));
            channel.displayName = metadata.optString("display_title", metadata.optString("network", channel.callSign));
            channel.major = metadata.optInt("major", 0);
            channel.minor = metadata.optInt("minor", 0);
            channel.kind = "ota";
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
