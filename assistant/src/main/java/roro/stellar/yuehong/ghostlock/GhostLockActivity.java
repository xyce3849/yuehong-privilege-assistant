package roro.stellar.yuehong.ghostlock;

import roro.stellar.yuehong.R;
import roro.stellar.yuehong.shell.GhostLockOtaApi;
import roro.stellar.yuehong.ui.DeviceInfoEntry;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.window.OnBackInvokedDispatcher;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.activity.ComponentActivity;
import androidx.compose.ui.platform.ComposeView;

public class GhostLockActivity extends ComponentActivity {
    private static final String TAG = "GhostLockApp";
    private static final String BINARY_NAME = "libghostlock.so";
    private static final String EXTRACT_NAME = "libextract.so";
    private static final String KSU_ACTIVATION_ASSET = "yhroot_ksu_activate.sh";
    private static final String KSU_ACTIVATION_SCRIPT = ".ghostlock_root.sh";
    private static final String OFFSETS_JSON = "offsets.json";
    private static final String PRESET_OFFSETS_ASSET = "offsets.json";
    private static final int REQ_IMPORT_OFFSETS = 1001;
    private static final int REQ_PICK_BOOT = 1002;
    private static final int REQ_PICK_XBL = 1003;
    private static final String PREFS = "ghostlock_prefs";
    private static final String PREF_CPU_PAIR = "cpu_pair";
    private static final int MAX_NATIVE_ATTEMPTS = 2;
    private static final int EXIT_KSU_ACTIVATION_FAILED = 2;
    private static final long NATIVE_RETRY_DELAY_MS = 1500L;
    private static final int COLOR_RED = 0xFFFF6B6B;
    private static final int COLOR_GREEN = 0xFF5FD68A;
    private static final int COLOR_YELLOW = 0xFFFFC94D;
    private static final int COLOR_BLUE = 0xFF60A5FA;
    private static final int COLOR_NONE = -1;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean automaticFlowRunning = new AtomicBoolean(false);
    private final StringBuilder logBuffer = new StringBuilder();
    private final List<int[]> cpuPairs = new ArrayList<>();
    private final List<String> cpuPairLabels = new ArrayList<>();
    private final Map<View, ValueAnimator> viewAnimators = new HashMap<>();
    private TextView deviceInfo;
    private ComposeView deviceInfoButtonHost;
    private TextView logView;
    private ScrollView logScroll;
    private Button copyButton;
    private Button otaButton;
    private Button autoRootButton;
    private Button importOffsetsButton;
    private EditText otaUrlInput;
    private GhostLockOtaApi ghostLockOtaApi;
    private View rootView;
    private int cpuPairIndex;
    private boolean parseWantsXbl;

    /**
     * Returns the value when it looks like a real device name, else null.
     */
    private static String validDeviceName(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown") || lower.contains("null")) {
            return null;
        }
        return v;
    }

    @SuppressLint("PrivateApi")
    private static String getSystemProperty(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class).invoke(null, key);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readSysFile(String path) {
        File f = new File(path);
        if (!f.isFile()) {
            return "";
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static List<Integer> parseCpuList(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (String part : s.split(",")) {
            String[] range = part.split("-");
            try {
                int lo = Integer.parseInt(range[0].trim());
                int hi = range.length > 1 ? Integer.parseInt(range[1].trim()) : lo;
                for (int cpu = lo; cpu <= hi; cpu++) {
                    out.add(cpu);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static long readMaxFreq(int cpu) {
        String v = readSysFile("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq");
        if (v.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String formatFreq(long khz) {
        if (khz >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2f GHz", khz / 1_000_000.0);
        }
        return String.format(Locale.ROOT, "%.0f MHz", khz / 1000.0);
    }

    /**
     * Color a whole log line by its leading marker. The native binary only
     * colors the "[..]" prefix (message text stays default) and the script log
     * is plain text, so per-line coloring is what makes the log readable.
     */
    private static CharSequence colorize(String line) {
        int color = markerColor(line);
        if (color == COLOR_NONE) {
            return line;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(line);
        sb.setSpan(new ForegroundColorSpan(color), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * Marker of the leading "[x] " tag, or 0 if the line has none.
     */
    private static char markerOf(String line) {
        return line.length() > 2 && line.charAt(0) == '[' && line.charAt(2) == ']' ? line.charAt(1) : 0;
    }

    /**
     * True for W1/W2/W3 stage round lines (progress or in-round failure).
     */
    private static boolean isWriteRound(String msg) {
        String stage = msg.startsWith("=== ") ? msg.substring(4) : msg;
        return stage.startsWith("W1") || stage.startsWith("W2") || stage.startsWith("W3") || stage.startsWith("Write 1");
    }

    private static int markerColor(String line) {
        char marker = markerOf(line);
        /* W-round progress renders blue; in-round failures keep red. */
        if (isWriteRound(logMessage(line))) {
            if (marker == '-' || marker == '!') return COLOR_RED;
            return COLOR_BLUE;
        }
        if (marker == '+') return COLOR_GREEN;
        if (marker == '-' || marker == '!') return COLOR_RED;
        if (marker == '*') return COLOR_YELLOW;
        if (line.startsWith("error") || line.startsWith("Error")) return COLOR_RED;
        if (line.startsWith("warning")) return COLOR_YELLOW;
        return COLOR_NONE;
    }

    /**
     * Strip leading "[..] " tags (marker, TIMER) and return the message.
     */
    private static String logMessage(String line) {
        String s = line;
        while (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) break;
            s = s.substring(end + 1);
            if (s.startsWith(" ")) s = s.substring(1);
        }
        return s;
    }

    private static String stripAnsi(String input) {
        return input.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static void setViewColor(View view, int color) {
        if (view.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) view.getBackground().mutate()).setColor(color);
        } else {
            view.setBackgroundTintList(ColorStateList.valueOf(color));
        }
    }

    private static String firstValidProperty(String... keys) {
        for (String key : keys) {
            String value = validDeviceName(getSystemProperty(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** A kernel can run only after OTA parsing stored a matching offsets.json. */
    private boolean isKernelSupported() {
        String version = System.getProperty("os.version", "");
        return importedOffsetsMatch(version);
    }

    /**
     * True when <filesDir>/offsets.json contains the current release.
     */
    private boolean importedOffsetsMatch(String version) {
        File offsets = new File(getFilesDir(), OFFSETS_JSON);
        if (!offsets.isFile()) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(offsets))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            Matcher matcher = Pattern.compile("\"release\"\\s*:\\s*\"([^\"]+)\"").matcher(sb);
            while (matcher.find()) {
                if (version.equals(matcher.group(1))) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private void buildCpuPairs() {
        cpuPairs.clear();
        cpuPairLabels.clear();
        List<Integer> online = parseCpuList(readSysFile("/sys/devices/system/cpu/online"));
        if (!online.isEmpty()) {
            Map<Long, List<Integer>> byFreq = new TreeMap<>(Collections.reverseOrder());
            for (int cpu : online) {
                long freq = readMaxFreq(cpu);
                if (freq > 0) {
                    byFreq.computeIfAbsent(freq, k -> new ArrayList<>()).add(cpu);
                }
            }
            for (Map.Entry<Long, List<Integer>> entry : byFreq.entrySet()) {
                List<Integer> cluster = entry.getValue();
                Collections.sort(cluster);
                String freqText = " · " + formatFreq(entry.getKey());
                for (int i = 0; i + 1 < cluster.size(); i += 2) {
                    int main = cluster.get(i);
                    int consumer = cluster.get(i + 1);
                    cpuPairs.add(new int[]{main, consumer});
                    cpuPairLabels.add(main + "," + consumer + freqText);
                }
            }
        }
        // Safe 0,1 fallback last: big cores are the default and listed first.
        boolean hasSafe = false;
        for (int[] pair : cpuPairs) {
            if (pair[0] == 0 && pair[1] == 1) {
                hasSafe = true;
                break;
            }
        }
        if (!hasSafe) {
            cpuPairs.add(new int[]{0, 1});
            long autoFreq = readMaxFreq(0);
            cpuPairLabels.add("0,1" + (autoFreq > 0 ? " · " + formatFreq(autoFreq) : ""));
        }
    }

    private void restoreCpuPair() {
        cpuPairIndex = 0;
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_CPU_PAIR, null);
        if (saved == null || saved.equals("auto")) {
            // Default to the big-core pair (first entry); a legacy "auto"
            // preference is treated the same.  The native side falls back to
            // 0/1 when the pair is unavailable.
            return;
        }
        String[] parts = saved.split(",");
        if (parts.length != 2) {
            return;
        }
        try {
            int main = Integer.parseInt(parts[0].trim());
            int consumer = Integer.parseInt(parts[1].trim());
            for (int i = 0; i < cpuPairs.size(); i++) {
                int[] pair = cpuPairs.get(i);
                if (pair[0] == main && pair[1] == consumer) {
                    cpuPairIndex = i;
                    return;
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghostlock);
        setupSystemBars();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> moveTaskToBack(true));
        }

        rootView = findViewById(R.id.root);
        deviceInfo = findViewById(R.id.deviceInfo);
        deviceInfoButtonHost = findViewById(R.id.deviceInfoButtonHost);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);
        copyButton = findViewById(R.id.copyButton);
        otaButton = findViewById(R.id.otaButton);
        autoRootButton = findViewById(R.id.autoRootButton);
        importOffsetsButton = findViewById(R.id.importOffsetsButton);
        otaUrlInput = findViewById(R.id.otaUrlInput);
        ghostLockOtaApi = new GhostLockOtaApi(this);
        DeviceInfoEntry.bind(this, deviceInfoButtonHost);

        applyWindowInsetsPadding();
        deviceInfo.setText(buildDeviceSummary());
        buildCpuPairs();
        restoreCpuPair();
        applyKernelStatus();
        setRunState(RunState.IDLE);

        copyButton.setOnClickListener(v -> copyLogs());
        otaButton.setOnClickListener(v -> promptParseUrl());
        autoRootButton.setOnClickListener(v -> startAutomaticPrivilege());
        importOffsetsButton.setOnClickListener(v -> importOffsets());
        installPressMotion(copyButton, otaButton, autoRootButton, importOffsetsButton);
        playEntryMotion();
    }

    @Override
    public void onBackPressed() {
        // 返回键仅将任务移到后台，保留正在运行的工作线程和服务。
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (ghostLockOtaApi != null) {
            ghostLockOtaApi.close();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    private void setupSystemBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) {
            return;
        }
        int lightStatus = getResources().getBoolean(R.bool.ghostlock_window_light_status_bar) ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0;
        int lightNav = getResources().getBoolean(R.bool.ghostlock_window_light_navigation_bar) ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
        controller.setSystemBarsAppearance(lightStatus | lightNav, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    /** 与 Compose 页面一致的轻量入场和按压反馈，不改变点击与无障碍行为。 */
    private void playEntryMotion() {
        rootView.animate().cancel();
        rootView.setAlpha(0f);
        rootView.setTranslationY(dp(12));
        rootView.post(() -> rootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(new DecelerateInterpolator())
                .start());
    }

    private void installPressMotion(View... views) {
        for (View view : views) {
            view.setOnTouchListener((target, event) -> {
                if (!target.isEnabled()) {
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    target.animate().cancel();
                    target.animate()
                            .scaleX(0.975f)
                            .scaleY(0.975f)
                            .alpha(0.92f)
                            .setDuration(90L)
                            .start();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                        event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    target.animate().cancel();
                    target.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(150L)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
                return false;
            });
        }
    }

    private void importOffsets() {
        pickDocument(REQ_IMPORT_OFFSETS);
    }

    /** Export one OTA-parsed kernel entry as a shareable offsets.json file. */
    private void exportOffsets() {
        String current = System.getProperty("os.version", "");
        Map<String, JSONObject> byRelease;
        try {
            byRelease = exportableEntries();
        } catch (IOException e) {
            appendLog("export offsets failed: " + e.getMessage());
            toast(R.string.ghostlock_export_failed);
            return;
        }
        if (byRelease.isEmpty()) {
            toast(R.string.ghostlock_export_none);
            return;
        }
        List<String> releases = new ArrayList<>(byRelease.keySet());
        releases.sort(Comparator.comparing((String release) -> release.equals(current) ? 0 : 1).thenComparing(Comparator.naturalOrder()));
        String[] labels = new String[releases.size()];
        for (int i = 0; i < releases.size(); i++) {
            String release = releases.get(i);
            labels[i] = release.equals(current) ? getString(R.string.ghostlock_export_current_marker, release) : release;
        }
        new AlertDialog.Builder(this).setTitle(R.string.ghostlock_export_title).setItems(labels, (dialog, which) -> {
            String release = releases.get(which);
            JSONObject entry = byRelease.get(release);
            worker.execute(() -> shareOffsets(entry, release));
        }).setNegativeButton(R.string.ghostlock_cancel, null).show();
    }

    /** OTA-parsed entries currently stored by the app. */
    private Map<String, JSONObject> exportableEntries() throws IOException {
        Map<String, JSONObject> byRelease = new LinkedHashMap<>();
        JSONArray imported = readOffsetsFile(new File(getFilesDir(), OFFSETS_JSON));
        if (imported != null) {
            for (int i = 0; i < imported.length(); i++) {
                JSONObject entry = imported.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String release = entry.optString("release", "");
                if (release.isEmpty()) {
                    continue;
                }
                byRelease.putIfAbsent(release, entry);
            }
        }
        return byRelease;
    }

    /**
     * Show the export button only when there is data worth sharing.
     */
    private void updateExportButton() {
        // 设置/导入/导出面板已从产品界面移除；偏移由 OTA 链接自动生成。
    }

    private void cancelViewAnimation(View view) {
        ValueAnimator running = viewAnimators.remove(view);
        if (running != null) {
            running.cancel();
        }
    }

    /**
     * Smoothly reveal a view: animate its height from 0 to the measured
     * content height while fading in.  The measure uses the parent width and
     * an unconstrained height, so padding and child margins are included in
     * the final size.
     */
    private void animateShow(View view) {
        cancelViewAnimation(view);
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        int width = view.getParent() instanceof View ? ((View) view.getParent()).getWidth() : 0;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int target = Math.max(view.getMeasuredHeight(), 1);
        lp.height = 0;
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofInt(0, target);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            view.setAlpha(a.getAnimatedFraction());
            view.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setAlpha(1f);
                view.requestLayout();
            }
        });
        anim.setDuration(220);
        viewAnimators.put(view, anim);
        anim.start();
    }

    /**
     * Smoothly hide a view: animate its height to 0 while fading out.
     */
    private void animateHide(View view) {
        cancelViewAnimation(view);
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        final int start = view.getHeight();
        ValueAnimator anim = ValueAnimator.ofInt(start, 0);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            view.setAlpha(1f - a.getAnimatedFraction());
            view.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setVisibility(View.GONE);
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setAlpha(1f);
            }
        });
        anim.setDuration(180);
        viewAnimators.put(view, anim);
        anim.start();
    }

    /**
     * Write one entry to Downloads and open the system share sheet.
     */
    private void shareOffsets(JSONObject entry, String release) {
        try {
            JSONArray payload = new JSONArray();
            payload.put(entry);
            String safeRelease = release.replaceAll("[^A-Za-z0-9._-]", "_");
            String name = "offsets-" + safeRelease + ".json";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("cannot create download entry");
            }
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("cannot open download entry");
                }
                out.write(payload.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            appendLog("exported offsets: " + name);
            final Uri sharedUri = uri;
            ui.post(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/json");
                send.putExtra(Intent.EXTRA_STREAM, sharedUri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, getString(R.string.ghostlock_export_share)));
            });
        } catch (Throwable t) {
            appendLog("export offsets failed: " + t.getMessage());
            ui.post(() -> toast(R.string.ghostlock_export_failed));
        }
    }

    private void pickDocument(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_PICK_BOOT || requestCode == REQ_PICK_XBL) {
            handleParsePick(requestCode, data);
            return;
        }
        if (requestCode != REQ_IMPORT_OFFSETS) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            toast(R.string.ghostlock_import_failed);
            return;
        }
        worker.execute(() -> {
            File target = new File(getFilesDir(), OFFSETS_JSON);
            try {
                File tmp = new File(getFilesDir(), "offsets_import.tmp");
                copyUriToFile(uri, tmp);
                JSONArray imported;
                try {
                    imported = readOffsetsFile(tmp);
                } finally {
                    tmp.delete();
                }
                if (imported == null) {
                    throw new IOException("not a valid offsets.json");
                }
                JSONArray existing = readOffsetsFile(target);
                if (existing == null) {
                    existing = new JSONArray();
                }
                final JSONArray existingEntries = existing;
                final JSONArray freshEntries = imported;
                List<String> overlaps = overlappingReleases(existingEntries, freshEntries);
                if (overlaps.isEmpty()) {
                    mergeAndSave(target, existingEntries, freshEntries, false);
                    ui.post(this::finishImport);
                } else {
                    ui.post(() -> promptOverwrite(overlaps, existingEntries, freshEntries, target));
                }
            } catch (IOException e) {
                appendLog("import offsets failed: " + e.getMessage());
                ui.post(() -> {
                    applyKernelStatus();
                    toast(R.string.ghostlock_import_failed);
                });
            }
        });
    }

    /**
     * Entry point of the "start privilege escalation" button.
     *
     * Execution contract (kept in sync with the OTA success path in runExtract):
     *
     *     OTA:  parse → mergeAndSave(release check) → startExploit → [gate] → native
     *     local: match → prepareOffsetsForExecution  → startExploit → [gate] → native
     *                     ^^^
     *                     this is the "B" step that must NOT be skipped: the
     *                     current-release entry has to be materialised into
     *                     filesDir/offsets.json BEFORE startExploit(), so that
     *                     startExploit()'s own isKernelSupported() gate reads
     *                     the real on-disk state — exactly what OTA does.
     */
    private void startAutomaticPrivilege() {
        if (running.get() || automaticFlowRunning.get()) {
            return;
        }

        String version = System.getProperty("os.version", "");
        if (version.isEmpty()) {
            appendLog("local: cannot determine current kernel release");
            toast(R.string.ghostlock_offsets_not_found);
            return;
        }

        // A: locate the entry matching the current kernel, in priority order:
        //    1) the on-disk store (filesDir/offsets.json) — same file OTA writes
        //    2) the bundled preset (assets/offsets.json)  — shipped fallback
        JSONObject matched = findLocalEntry(version);
        if (matched == null) {
            appendLog("no local offsets match current kernel: " + version);
            toast(R.string.ghostlock_offsets_not_found);
            autoRootButton.setText(R.string.ghostlock_action_start_privilege);
            return;
        }

        // A→B: materialise the matched entry into filesDir/offsets.json.
        // This is the exact counterpart of the OTA path's
        //   mergeAndSave(offsets, existing, imported, true)   [runExtract:1003]
        // followed by startExploit().  It deliberately runs on the worker
        // thread, just like the OTA merge, so startExploit() sees a consistent
        // file when it checks isKernelSupported() on the UI thread next.
        final JSONObject entry = matched;
        worker.execute(() -> {
            try {
                prepareOffsetsForExecution(entry, version);
                ui.post(() -> {
                    appendLog("local offsets matched & materialised: " + version);
                    toast(R.string.ghostlock_offsets_matched);
                    autoRootButton.setText(R.string.ghostlock_action_local_ready);
                    // B→C→D→native: identical entry point to the OTA path.
                    startExploit();
                });
            } catch (IOException e) {
                appendLog("local offsets materialise failed: " + e.getMessage());
                ui.post(() -> toast(R.string.ghostlock_import_failed));
            }
        });
    }

    /**
     * B step — mirrors runExtract's post-parse sequence (lines ~987-1009):
     *
     *   1. read existing filesDir/offsets.json
     *   2. validate the entry's release matches the current kernel
     *   3. mergeAndSave(..., overwrite=true) so the current release is the
     *      definitive entry on disk
     *
     * After this returns successfully, startExploit()'s isKernelSupported()
     * gate is guaranteed to pass against the real file.
     */
    private void prepareOffsetsForExecution(JSONObject entry, String currentRelease) throws IOException {
        String release = entry.optString("release", "");
        if (!currentRelease.equals(release)) {
            throw new IOException("local entry release mismatch: entry=" + release + ", current=" + currentRelease);
        }
        File offsets = new File(getFilesDir(), OFFSETS_JSON);
        JSONArray existing = readOffsetsFile(offsets);
        if (existing == null) {
            existing = new JSONArray();
        }
        JSONArray imported = new JSONArray();
        imported.put(entry);
        // overwrite=true: OTA's contract — the freshly resolved current-release
        // entry always wins over any stale on-disk value.
        mergeAndSave(offsets, existing, imported, true);
        appendLog("local offsets written: " + offsets.getAbsolutePath());
    }

    /**
     * Search order for a local entry matching {@code release}:
     *  1. filesDir/offsets.json   — the runtime store (OTA also writes here)
     *  2. assets/offsets.json     — preset shipped with the APK
     *
     * Returns the first matching JSONObject, or null.
     */
    private JSONObject findLocalEntry(String release) {
        // 1) on-disk store
        JSONObject fromDisk = findEntryInFile(new File(getFilesDir(), OFFSETS_JSON), release);
        if (fromDisk != null) {
            return fromDisk;
        }
        // 2) bundled preset
        return readPresetEntry(release);
    }

    private JSONObject findEntryInFile(File file, String release) {
        try {
            JSONArray arr = readOffsetsFile(file);
            if (arr == null) {
                return null;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e != null && release.equals(e.optString("release", ""))) {
                    return e;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * Read the preset shipped in assets/offsets.json.  Supports both a single
     * object and an array of entries — same convention as readOffsetsFile().
     */
    private JSONObject readPresetEntry(String release) {
        try (InputStream in = getAssets().open(PRESET_OFFSETS_ASSET)) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            Object value = new JSONTokener(sb.toString()).nextValue();
            JSONArray arr;
            if (value instanceof JSONArray) {
                arr = (JSONArray) value;
            } else if (value instanceof JSONObject) {
                arr = new JSONArray();
                arr.put(value);
            } else {
                return null;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e != null && release.equals(e.optString("release", ""))) {
                    return e;
                }
            }
        } catch (IOException | JSONException ignored) {
        }
        return null;
    }

    private void pickParseBoot(boolean withXbl) {
        parseWantsXbl = withXbl;
        if (withXbl) {
            toast(R.string.ghostlock_parse_pick_boot_hint);
        }
        pickDocument(REQ_PICK_BOOT);
    }

    private void handleParsePick(int requestCode, Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        worker.execute(() -> {
            try {
                File workDir = getFilesDir();
                File boot = new File(workDir, "boot.img");
                if (requestCode == REQ_PICK_BOOT) {
                    copyUriToFile(uri, boot);
                    appendLog("boot.img ready: " + boot.getAbsolutePath());
                    if (parseWantsXbl) {
                        ui.post(() -> {
                            toast(R.string.ghostlock_parse_pick_xbl_hint);
                            pickDocument(REQ_PICK_XBL);
                        });
                    } else {
                        ui.post(() -> runExtract(boot.getAbsolutePath(), null));
                    }
                } else {
                    File xbl = new File(workDir, "xbl_config.img");
                    copyUriToFile(uri, xbl);
                    appendLog("xbl_config.img ready: " + xbl.getAbsolutePath());
                    ui.post(() -> runExtract(boot.getAbsolutePath(), xbl));
                }
            } catch (IOException e) {
                appendLog("parse error: " + e.getMessage());
            }
        });
    }

    private void copyUriToFile(Uri uri, File target) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(target, false)) {
            if (in == null) {
                throw new IOException("cannot open " + uri);
            }
            copyStream(in, out);
        }
    }

    /**
     * Parse an offsets file (single object or array) into an array of entries.
     */
    private JSONArray readOffsetsFile(File file) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        if (sb.toString().trim().isEmpty()) {
            return null;
        }
        try {
            Object value = new JSONTokener(sb.toString()).nextValue();
            if (value instanceof JSONArray) {
                return (JSONArray) value;
            }
            if (value instanceof JSONObject) {
                JSONArray arr = new JSONArray();
                arr.put(value);
                return arr;
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    private Set<String> importedReleases(JSONArray entries) {
        Set<String> releases = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null) {
                releases.add(entry.optString("release", ""));
            }
        }
        return releases;
    }

    /**
     * Releases already present in the store that the import also contains.
     */
    private List<String> overlappingReleases(JSONArray existing, JSONArray imported) {
        Set<String> known = importedReleases(existing);
        List<String> overlaps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < imported.length(); i++) {
            JSONObject entry = imported.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String release = entry.optString("release", "");
            if (!release.isEmpty() && known.contains(release) && seen.add(release)) {
                overlaps.add(release);
            }
        }
        return overlaps;
    }

    /**
     * Merge `imported` into `existing` and write the result back.  With
     * overwrite=true imported entries replace stored entries with the same
     * release; otherwise the stored values are kept.
     */
    private void mergeAndSave(File target, JSONArray existing, JSONArray imported, boolean overwrite) throws IOException {
        Map<String, JSONObject> importedByRelease = new HashMap<>();
        for (int i = 0; i < imported.length(); i++) {
            JSONObject entry = imported.optJSONObject(i);
            if (entry != null) {
                importedByRelease.put(entry.optString("release", ""), entry);
            }
        }
        Set<String> known = importedReleases(existing);
        JSONArray merged = new JSONArray();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject entry = existing.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            if (overwrite && importedByRelease.containsKey(entry.optString("release", ""))) {
                continue;
            }
            merged.put(entry);
        }
        for (int i = 0; i < imported.length(); i++) {
            JSONObject entry = imported.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            if (!overwrite && known.contains(entry.optString("release", ""))) {
                continue;
            }
            merged.put(entry);
        }
        try (OutputStream out = new FileOutputStream(target, false)) {
            out.write(merged.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("serialize offsets failed", e);
        }
    }

    /** Ask whether stored OTA offsets should be replaced. */
    private void promptOverwrite(List<String> replaced, JSONArray existing, JSONArray imported, File target) {
        new AlertDialog.Builder(this).setTitle(R.string.ghostlock_overwrite_title).setMessage(getString(R.string.ghostlock_overwrite_message, String.join("\n", replaced))).setPositiveButton(R.string.ghostlock_overwrite_yes, (dialog, which) -> worker.execute(() -> {
            try {
                mergeAndSave(target, existing, imported, true);
                ui.post(this::finishImport);
            } catch (IOException e) {
                appendLog("import offsets failed: " + e.getMessage());
                ui.post(() -> {
                    applyKernelStatus();
                    toast(R.string.ghostlock_import_failed);
                });
            }
        })).setNegativeButton(R.string.ghostlock_cancel, null).show();
    }

    private void finishImport() {
        applyKernelStatus();
        toast(R.string.ghostlock_import_success);
        appendLog("offsets.json imported: " + new File(getFilesDir(), OFFSETS_JSON).getAbsolutePath());
    }

    /**
     * Run the extractor on an OTA URL or local boot/xbl files; writes offsets.json on success.
     */
    private void runExtract(String input, File xblFile) {
        runExtract(input, xblFile, false);
    }

    private void runExtract(String input, File xblFile, boolean automaticFlow) {
        worker.execute(() -> {
            int code = 1;
            try {
                File binary = new File(getApplicationInfo().nativeLibraryDir, EXTRACT_NAME);
                if (!binary.isFile()) {
                    throw new IOException("missing native binary: " + binary.getAbsolutePath());
                }
                File workDir = getFilesDir();
                List<String> args = new ArrayList<>();
                args.add(input);
                if (xblFile != null) {
                    args.add("--xbl-config");
                    args.add(xblFile.getAbsolutePath());
                }
                args.add("--format");
                args.add("json");
                args.add("--out");
                args.add(new File(workDir, "offsets_parse.tmp").getAbsolutePath());
                args.add("--work-dir");
                args.add(workDir.getAbsolutePath());
                appendLog("extract: " + input);
                code = runExtractProcess(binary, args, workDir);
                appendLog("extract exit code=" + code);
                File offsets = new File(workDir, OFFSETS_JSON);
                File parsed = new File(workDir, "offsets_parse.tmp");
                boolean ok = code == 0 && parsed.isFile();
                Runnable uiResult = null;
                try {
                    if (ok) {
                        JSONArray imported = readOffsetsFile(parsed);
                        if (imported == null) {
                            ok = false;
                        } else {
                            String currentRelease = System.getProperty("os.version", "");
                            if (!importedReleases(imported).contains(currentRelease)) {
                                throw new IOException("parsed OTA release does not match current kernel: " + currentRelease);
                            }
                            JSONArray existing = readOffsetsFile(offsets);
                            if (existing == null) {
                                existing = new JSONArray();
                            }
                            // OTA parsing is the sole source of runtime offsets.
                            // Always persist the freshly parsed current-release entry,
                            // then execute with that offsets.json immediately.
                            mergeAndSave(offsets, existing, imported, true);
                            uiResult = () -> {
                                toast(R.string.ghostlock_parse_success);
                                appendLog("OTA offsets written: " + offsets.getAbsolutePath());
                                appendLog("starting with OTA-parsed offsets");
                                startExploit();
                            };
                        }
                    }
                } catch (IOException e) {
                    appendLog("merge offsets failed: " + e.getMessage());
                    ok = false;
                } finally {
                    parsed.delete();
                }
                final int extractCode = code;
                final Runnable result = uiResult;
                ui.post(() -> {
                    if (automaticFlow) {
                        automaticFlowRunning.set(false);
                    }
                    applyKernelStatus();
                    if (result != null) {
                        result.run();
                    } else {
                        if (automaticFlow) {
                            setRunState(RunState.FAILED);
                        }
                        toast(parseFailureToast(extractCode));
                    }
                });
            } catch (Throwable t) {
                appendLog("error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                if (automaticFlow) {
                    automaticFlowRunning.set(false);
                    ui.post(() -> setRunState(RunState.FAILED));
                }
                ui.post(() -> toast(R.string.ghostlock_parse_failed));
            }
        });
    }

    /**
     * Map an extractor exit code to the user-facing failure message.
     * 3: pselect route infeasible, 4: missing required offsets, 5: kallsyms
     * recovery failure; -1 means the process was killed on timeout.
     */
    private int parseFailureToast(int code) {
        return switch (code) {
            case 3, 4 -> R.string.ghostlock_parse_failed_route;
            case 5 -> R.string.ghostlock_parse_failed_kallsyms;
            case -1 -> R.string.ghostlock_parse_timeout;
            default -> R.string.ghostlock_parse_failed;
        };
    }

    private int runExtractProcess(File binary, List<String> args, File workDir) throws IOException, InterruptedException {
        // The extractor recovers kallsyms from the image itself; no root needed.
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.getAbsolutePath());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("GHOSTLOCK_HOME", workDir.getAbsolutePath());
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        pb.environment().put("HOME", workDir.getAbsolutePath());
        // Payload downloads and on-device analysis can take a while.
        return runProcess(pb, 1800);
    }

    private void applyWindowInsetsPadding() {
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            top = bars.top;
            bottom = bars.bottom;
            int side = dp(14);
            v.setPadding(side, top + dp(8), side, bottom + dp(8));
            return insets;
        });
        rootView.requestApplyInsets();
    }

    private String buildDeviceSummary() {
        return getString(R.string.ghostlock_device_label) + ": " + resolveDeviceName() + "\n" + getString(R.string.ghostlock_kernel_label) + ": " + System.getProperty("os.version", "unknown");
    }

    private void applyKernelStatus() {
        // 支持判断仅在执行链内部使用，不再占用主界面空间显示状态控件。
    }

    /**
     * Sales/market name of the device, matching the language of the current region.
     */
    private String resolveDeviceName() {
        boolean cn = "CN".equalsIgnoreCase(Locale.getDefault().getCountry());
        String marketName = firstValidProperty(cn ? "ro.vendor.oplus.market.name" : "ro.vendor.oplus.market.enname", cn ? "ro.vendor.oplus.market.enname" : "ro.vendor.oplus.market.name", "ro.product.marketname");
        return marketName != null ? marketName : Build.MANUFACTURER + " " + Build.MODEL;
    }

    private void startExploit() {
        if (!isKernelSupported()) {
            appendLog("no OTA-parsed offsets match the current kernel");
            toast(R.string.ghostlock_parse_failed);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        setRunState(RunState.RUNNING);
        // keep the screen awake
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        appendLog("==== start ====");
        appendLog("cpu pair: " + cpuPairLabels.get(cpuPairIndex));
        worker.execute(() -> {
            int code = 1;
            try {
                File workDir = getFilesDir();
                File binary = resolveBinary();
                code = runBinaryWithRetry(binary, workDir);
                appendLog("exit code=" + code);
            } catch (Throwable t) {
                appendLog("error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                int finalCode = code;
                ui.post(() -> {
                    running.set(false);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (finalCode == 0) {
                        setRunState(RunState.SUCCESS);
                    } else {
                        setRunState(RunState.FAILED);
                    }
                });
            }
        });
    }

    private void setRunState(RunState state) {
        boolean busy = state == RunState.RUNNING || automaticFlowRunning.get();
        otaButton.setEnabled(!busy);
        autoRootButton.setEnabled(!busy);
        otaButton.setText(state == RunState.RUNNING ? R.string.ghostlock_action_running : R.string.ghostlock_action_parse_ota);
        autoRootButton.setText(busy ? R.string.ghostlock_action_running : R.string.ghostlock_action_start_privilege);
    }

    private File resolveBinary() throws IOException {
        File packaged = new File(getApplicationInfo().nativeLibraryDir, BINARY_NAME);
        if (packaged.isFile()) {
            appendLog("binary ready (" + packaged.length() + " bytes)");
            return packaged;
        }
        throw new IOException("missing native binary: " + packaged.getAbsolutePath());
    }

    private File prepareKsuActivationScript(File workDir) throws IOException {
        File out = new File(workDir, KSU_ACTIVATION_SCRIPT);
        try (InputStream in = getAssets().open(KSU_ACTIVATION_ASSET);
             OutputStream output = new FileOutputStream(out, false)) {
            copyStream(in, output);
        }
        return out;
    }
}
