package com.zalexdev.stryker.appintro.slides;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.zalexdev.stryker.BuildConfig;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.install.LogAdapter;
import com.zalexdev.stryker.appintro.install.LogLevel;
import com.zalexdev.stryker.appintro.install.LogLine;
import com.zalexdev.stryker.engine.QemuInstaller;
import com.zalexdev.stryker.engine.RootlessEngine;
import com.zalexdev.stryker.engine.VmSpecs;
import com.zalexdev.stryker.utils.Core;

import java.util.EnumMap;
import java.util.Locale;

public class SlideQemuInstall extends Fragment {

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private TextView statusTitle;
    private TextView statusSubtitle;
    private ImageView statusIcon;
    private ProgressBar statusSpinner;

    private LinearLayout downloadBlock;
    private LinearProgressIndicator progress;
    private TextView downloadText;

    private TextView stagesHeader;
    private LinearLayout stagesContainer;
    private View stagesCard;

    private TextView logHeader;
    private View logCard;
    private RecyclerView logRecycler;
    private LogAdapter logAdapter;

    private MaterialButton installButton;

    private final EnumMap<QemuInstaller.Stage, StageRow> stageRows = new EnumMap<>(QemuInstaller.Stage.class);

    private boolean started = false;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide_qemu, container, false);
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);

        statusTitle = view.findViewById(R.id.status_title);
        statusSubtitle = view.findViewById(R.id.status_subtitle);
        statusIcon = view.findViewById(R.id.status_icon);
        statusSpinner = view.findViewById(R.id.status_spinner);

        downloadBlock = view.findViewById(R.id.download_block);
        progress = view.findViewById(R.id.slide_install_progress);
        downloadText = view.findViewById(R.id.download_text);

        stagesHeader = view.findViewById(R.id.stages_header);
        stagesContainer = view.findViewById(R.id.stages_container);
        stagesCard = view.findViewById(R.id.stages_card);

        logHeader = view.findViewById(R.id.log_header);
        logCard = view.findViewById(R.id.log_card);
        logRecycler = view.findViewById(R.id.log_recycler);
        logRecycler.setLayoutManager(new LinearLayoutManager(context));
        logAdapter = new LogAdapter(context);
        logRecycler.setAdapter(logAdapter);

        installButton = view.findViewById(R.id.login);

        buildStageRows(inflater);

        installButton.setOnClickListener(v -> {
            if (channelChosen()) startInstall();
            else promptChannel();
        });
        return view;
    }

    private boolean channelChosen() {
        return activity != null && activity.getIntent() != null
                && activity.getIntent().getBooleanExtra(
                        com.zalexdev.stryker.appintro.AppIntroActivity.EXTRA_CHANNEL_CHOSEN, false);
    }

    private void promptChannel() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("Image channel")
                .setMessage("Which rootless image should be installed?")
                .setPositiveButton("Stable (main)", (d, w) -> {
                    com.zalexdev.stryker.ota.QemuDownloader.setUseTest(context, false);
                    startInstall();
                })
                .setNeutralButton("Test (650)", (d, w) -> {
                    com.zalexdev.stryker.ota.QemuDownloader.setUseTest(context, true);
                    startInstall();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startInstall() {
        started = true;
        installButton.setVisibility(View.INVISIBLE);
        stagesHeader.setVisibility(View.VISIBLE);
        stagesContainer.setVisibility(View.VISIBLE);
        stagesCard.setVisibility(View.VISIBLE);
        logHeader.setVisibility(View.VISIBLE);
        logCard.setVisibility(View.VISIBLE);
        logRecycler.setVisibility(View.VISIBLE);
        setStatus(StatusKind.RUNNING, "Rootless engine", "Starting...");
        log(LogLevel.INFO, "Stryker " + BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE);
        log(LogLevel.INFO, "Engine: rootless (QEMU aarch64)");

        runOnUi(() -> {
            downloadBlock.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
        });

        new Thread(() -> {
            if (QemuInstaller.assetsPresent(context)) {
                log(LogLevel.INFO, "Artifacts bundled in the APK — installing offline");
            } else {
                log(LogLevel.INFO, "Artifacts not bundled — downloading (~550 MB)");
            }

            boolean ok = QemuInstaller.install(context, new QemuInstaller.Progress() {
                @Override public void onStage(QemuInstaller.Stage stage) { advanceTo(stage); }
                @Override public void onBytes(String label, long done) {
                    runOnUi(() -> downloadText.setText(label + " · " + formatMb(done)));
                }
                @Override public void onLog(int level, String message) {
                    log(mapLevel(level), message);
                }
            });

            if (!ok) {
                failWith("Extraction failed — see log");
                return;
            }
            runOnUi(() -> downloadBlock.setVisibility(View.GONE));

            seedDefaults();
            log(LogLevel.SUCCESS, "Defaults written (wlan0, "
                    + core.getInt("rootless_cpus", VmSpecs.DEFAULT_CPUS) + " vCPU, "
                    + core.getInt("rootless_ram", VmSpecs.DEFAULT_RAM_MB) + " MB)");

            setStatus(StatusKind.RUNNING, "Rootless engine", "Booting VM (first boot is slow)...");
            log(LogLevel.STEP, "Booting QEMU VM for the first time");
            boolean booted = RootlessEngine.get(context).startBlocking(new RootlessEngine.BootListener() {
                @Override public void onBootLine(String line) {
                    if (line != null && (line.contains("stryker") || line.contains("login")
                            || line.contains("Kernel panic") || line.contains("error"))) {
                        log(LogLevel.INFO, line);
                    }
                }
                @Override public void onBooted() { log(LogLevel.SUCCESS, "Guest is up"); }
                @Override public void onFailed(String reason) { log(LogLevel.WARN, "Boot: " + reason); }
            });

            if (booted) {
                log(LogLevel.SUCCESS, "Rootless engine ready");
                log(LogLevel.STEP, "Deploying built-in scripts (CORE, exploits)");
                boolean coreOk = RootlessEngine.get(context).ensureGuestCore();
                log(coreOk ? LogLevel.SUCCESS : LogLevel.WARN,
                        coreOk ? "Scripts deployed to the guest" : "Scripts deploy deferred to first use");
            } else {
                log(LogLevel.WARN, "VM did not report ready — it will retry on first use");
            }

            setStatus(StatusKind.SUCCESS, "Rootless engine", "Installation complete — moving on...");
            runOnUi(() -> {
                progress.setVisibility(View.INVISIBLE);
                core.moveNext(mPager);
            });
        }).start();
    }

    private void seedDefaults() {
        if (core.getString("wlan_wifi").isEmpty())   core.putString("wlan_wifi", Core.WIFI_INTERNAL);
        if (core.getString("wlan_scan").isEmpty())   core.putString("wlan_scan", "wlan0");
        if (core.getString("wlan_deauth").isEmpty()) core.putString("wlan_deauth", "wlan0");
        if (core.getString("wlan_wps").isEmpty())    core.putString("wlan_wps", "wlan0");
        if (core.getInt("rootless_ram", 0) <= 0)
            core.putInt("rootless_ram", VmSpecs.recommendedRamMb(context));
        if (core.getInt("rootless_cpus", 0) <= 0)
            core.putInt("rootless_cpus", VmSpecs.recommendedCpus());
    }

    private void failWith(String reason) {
        setStatus(StatusKind.FAILED, "Rootless engine", reason);
        log(LogLevel.ERROR, reason);
        runOnUi(() -> {
            progress.setIndeterminate(false);
            downloadBlock.setVisibility(View.GONE);
            installButton.setText(R.string.try_again);
            installButton.setVisibility(View.VISIBLE);
            started = false;
        });
    }

    private static String formatMb(long bytes) {
        if (bytes <= 0) return "0 MB";
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static LogLevel mapLevel(int level) {
        switch (level) {
            case 3: return LogLevel.ERROR;
            case 2: return LogLevel.SUCCESS;
            default: return LogLevel.STEP;
        }
    }

    private void runOnUi(Runnable r) {
        if (activity == null || !isAdded()) return;
        activity.runOnUiThread(() -> { if (isAdded()) r.run(); });
    }

    private void log(LogLevel level, String text) {
        runOnUi(() -> {
            logAdapter.append(new LogLine(level, text));
            if (logAdapter.size() > 0) logRecycler.scrollToPosition(logAdapter.size() - 1);
        });
    }

    private enum StatusKind { RUNNING, SUCCESS, FAILED }

    @SuppressLint("SetTextI18n")
    private void setStatus(StatusKind kind, String title, String subtitle) {
        runOnUi(() -> {
            statusTitle.setText(title);
            statusSubtitle.setText(subtitle);
            switch (kind) {
                case SUCCESS:
                    statusSpinner.setVisibility(View.GONE);
                    statusIcon.setVisibility(View.VISIBLE);
                    statusIcon.setImageResource(R.drawable.done);
                    statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.green), PorterDuff.Mode.SRC_IN);
                    break;
                case FAILED:
                    statusSpinner.setVisibility(View.GONE);
                    statusIcon.setVisibility(View.VISIBLE);
                    statusIcon.setImageResource(R.drawable.error);
                    statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.red), PorterDuff.Mode.SRC_IN);
                    break;
                case RUNNING:
                default:
                    statusIcon.setVisibility(View.GONE);
                    statusIcon.clearColorFilter();
                    statusSpinner.setVisibility(View.VISIBLE);
                    break;
            }
        });
    }

    private enum RowState { PENDING, ACTIVE, DONE, FAILED }

    private void buildStageRows(LayoutInflater inflater) {
        stagesContainer.removeAllViews();
        stageRows.clear();
        for (QemuInstaller.Stage stage : QemuInstaller.Stage.values()) {
            View row = inflater.inflate(R.layout.install_stage_row, stagesContainer, false);
            TextView title = row.findViewById(R.id.stage_title);
            ImageView icon = row.findViewById(R.id.stage_icon);
            ProgressBar spinner = row.findViewById(R.id.stage_spinner);
            FrameLayout indicator = row.findViewById(R.id.stage_indicator);
            title.setText(stage.title);
            StageRow handles = new StageRow(title, icon, spinner, indicator);
            applyRowState(handles, RowState.PENDING);
            stageRows.put(stage, handles);
            stagesContainer.addView(row);
        }
    }

    private void advanceTo(QemuInstaller.Stage current) {
        runOnUi(() -> {
            boolean reachedCurrent = false;
            for (QemuInstaller.Stage s : QemuInstaller.Stage.values()) {
                StageRow row = stageRows.get(s);
                if (row == null) continue;
                if (s == current) {
                    reachedCurrent = true;
                    applyRowState(row, current == QemuInstaller.Stage.DONE ? RowState.DONE : RowState.ACTIVE);
                    statusSubtitle.setText(s.title);
                } else if (!reachedCurrent) {
                    applyRowState(row, RowState.DONE);
                }
            }
        });
    }

    private void applyRowState(StageRow row, RowState state) {
        int color;
        switch (state) {
            case ACTIVE:
                color = ContextCompat.getColor(context, R.color.stryker_accent);
                row.spinner.setVisibility(View.VISIBLE);
                row.icon.setVisibility(View.GONE);
                row.title.setTypeface(null, android.graphics.Typeface.BOLD);
                break;
            case DONE:
                color = ContextCompat.getColor(context, R.color.green);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setImageResource(R.drawable.done);
                row.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                row.title.setTypeface(null, android.graphics.Typeface.NORMAL);
                break;
            case FAILED:
                color = ContextCompat.getColor(context, R.color.red);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setImageResource(R.drawable.error);
                row.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                row.title.setTypeface(null, android.graphics.Typeface.BOLD);
                break;
            case PENDING:
            default:
                color = ContextCompat.getColor(context, R.color.grey);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.GONE);
                row.title.setTypeface(null, android.graphics.Typeface.NORMAL);
                break;
        }
        row.title.setTextColor(color);
        if (row.indicator.getBackground() != null) {
            row.indicator.getBackground().mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
            row.indicator.getBackground().setAlpha(60);
        }
    }

    private static final class StageRow {
        final TextView title;
        final ImageView icon;
        final ProgressBar spinner;
        final FrameLayout indicator;

        StageRow(TextView title, ImageView icon, ProgressBar spinner, FrameLayout indicator) {
            this.title = title;
            this.icon = icon;
            this.spinner = spinner;
            this.indicator = indicator;
        }
    }
}
