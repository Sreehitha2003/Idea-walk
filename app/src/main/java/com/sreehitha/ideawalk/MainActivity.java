package com.sreehitha.ideawalk;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Idea Walk: a WebView app (UI lives in assets/index.html) plus a small native
 * bridge for the two things a web page can't do well on Android:
 *   1. continuous speech-to-text using the phone's own speech recogniser
 *   2. calling the Claude API with a key kept in the app's private storage
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 42;
    private static final String DEFAULT_MODEL = "claude-sonnet-5";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private WebView web;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private boolean pendingStart = false;
    private int errorStreak = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ideawalk", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Let the page close a sheet or switch back to the Record tab first.
        web.evaluateJavascript("(window.onBack && window.onBack()) ? 'handled' : 'no'", value -> {
            if (value == null || !value.contains("handled")) super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        listening = false;
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- events to JS

    private void emit(JSONObject event) {
        final String js = "window.onNative && window.onNative(" + event.toString() + ")";
        main.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private void emit(String type, String key, Object value) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            if (key != null) o.put(key, value);
            emit(o);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- speech

    private void requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        beginSession();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingStart) {
            beginSession();
        } else if (!granted) {
            emit("speech_error", "message", "Microphone permission was denied. Allow it in Settings > Apps > Idea Walk > Permissions, or use your keyboard's mic.");
            emit("speech_state", "on", false);
        }
        pendingStart = false;
    }

    private void beginSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("speech_error", "message", "This phone has no speech recogniser available. Install or enable the Google app, or use your keyboard's mic.");
            emit("speech_state", "on", false);
            return;
        }
        listening = true;
        errorStreak = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        emit("speech_state", "on", true);
        startCycle();
    }

    private void ensureRecognizer() {
        if (recognizer != null) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onPartialResults(Bundle partial) {
                String t = best(partial);
                if (t != null) emit("speech_partial", "text", t);
            }

            @Override
            public void onResults(Bundle results) {
                errorStreak = 0;
                String t = best(results);
                if (t != null && !t.trim().isEmpty()) emit("speech_final", "text", t);
                else emit("speech_partial", "text", "");
                if (listening) main.postDelayed(MainActivity.this::startCycle, 150);
                else finishSession();
            }

            @Override
            public void onError(int error) {
                if (!listening) { finishSession(); return; }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    emit("speech_error", "message", "Microphone permission is off for Idea Walk.");
                    stopSession();
                    return;
                }
                boolean pause = error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                if (!pause) errorStreak++;
                if (errorStreak > 8) {
                    emit("speech_error", "message", "Speech recognition keeps failing (code " + error + "). Check your connection, or use your keyboard's mic.");
                    stopSession();
                    return;
                }
                // Busy / client errors: throw the recogniser away and make a fresh one.
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    recognizer.destroy();
                    recognizer = null;
                }
                main.postDelayed(MainActivity.this::startCycle, pause ? 100 : 400L * errorStreak);
            }
        });
    }

    private void startCycle() {
        if (!listening) return;
        ensureRecognizer();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Ask for long pauses before the recogniser decides you've finished (not every phone honours these).
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            emit("speech_error", "message", "Couldn't start listening: " + e.getMessage());
            stopSession();
        }
    }

    private void stopSession() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
        }
        // onResults usually follows stopListening(); make sure the UI settles even if it doesn't.
        main.postDelayed(this::finishSession, 1500);
    }

    private void finishSession() {
        if (listening) return; // a new session started in the meantime
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        emit("speech_state", "on", listening);
    }

    private static String best(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    // ---------------------------------------------------------------- Claude API

    private void callClaude(String prompt, String id) {
        new Thread(() -> {
            JSONObject out = new JSONObject();
            HttpURLConnection c = null;
            try {
                out.put("type", "claude");
                out.put("id", id);
                String key = prefs.getString("api_key", "");
                if (key.isEmpty()) throw new IllegalStateException("Add your Claude API key in Settings first.");

                JSONObject body = new JSONObject();
                body.put("model", prefs.getString("model", DEFAULT_MODEL));
                body.put("max_tokens", 4096);
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "user").put("content", prompt));
                body.put("messages", msgs);

                c = (HttpURLConnection) new URL(API_URL).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(20000);
                c.setReadTimeout(120000);
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                c.setRequestProperty("x-api-key", key);
                c.setRequestProperty("anthropic-version", "2023-06-01");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = c.getResponseCode();
                String raw = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code >= 400) {
                    String msg = "Claude API error " + code;
                    try { msg = new JSONObject(raw).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
                    if (code == 401) msg = "Your API key was rejected. Check it in Settings.";
                    if (code == 429) msg = "Rate limited by the Claude API. Wait a minute and try again.";
                    throw new IllegalStateException(msg);
                }

                JSONArray content = new JSONObject(raw).getJSONArray("content");
                StringBuilder text = new StringBuilder();
                for (int k = 0; k < content.length(); k++) {
                    JSONObject block = content.getJSONObject(k);
                    if ("text".equals(block.optString("type"))) text.append(block.optString("text"));
                }
                out.put("ok", true);
                out.put("text", text.toString());
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    String m = e.getMessage();
                    if (e instanceof java.net.UnknownHostException) m = "No internet connection. Your transcript is saved; try again later.";
                    if (e instanceof java.net.SocketTimeoutException) m = "Claude took too long to answer. Try again.";
                    out.put("error", m == null ? e.toString() : m);
                } catch (Exception ignored) { }
            } finally {
                if (c != null) c.disconnect();
            }
            emit(out);
        }).start();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- bridge

    /** Methods callable from the page as window.Android.method(...). They run off the UI thread. */
    private class Bridge {
        @JavascriptInterface public void startListening() { main.post(MainActivity.this::requestStart); }
        @JavascriptInterface public void stopListening() { main.post(MainActivity.this::stopSession); }

        @JavascriptInterface public boolean hasApiKey() { return !prefs.getString("api_key", "").isEmpty(); }
        @JavascriptInterface public void setApiKey(String key) { prefs.edit().putString("api_key", key == null ? "" : key.trim()).apply(); }
        @JavascriptInterface public String getModel() { return prefs.getString("model", DEFAULT_MODEL); }
        @JavascriptInterface public void setModel(String m) {
            String v = (m == null || m.trim().isEmpty()) ? DEFAULT_MODEL : m.trim();
            prefs.edit().putString("model", v).apply();
        }

        @JavascriptInterface public void askClaude(String prompt, String id) { callClaude(prompt, id); }

        @JavascriptInterface public void share(String text) {
            main.post(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "Share"));
            });
        }
    }
}
