package com.zalexdev.stryker.dashboard;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.arsenal.ArsenalFragment;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.engine.RootlessEngine;
import com.zalexdev.stryker.engine.RootlessService;
import com.zalexdev.stryker.engine.VmBootStage;
import com.zalexdev.stryker.engine.VmStatsCollector;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.SparklineView;
import com.zalexdev.stryker.utils.VmRingView;

import net.cachapa.expandablelayout.ExpandableLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class Dashboard extends Fragment {

    private Activity activity;
    private Context context;
    private Core core;
    private final MainActivity.Receiver receiver = new MainActivity.Receiver();

    private RootlessEngine vmEngine;
    private View vmCard;
    private TextView vmBadge, vmSpecs, vmUsb, vmStatusChevron, vmLogsChevron, vmLogText;
    private android.widget.ScrollView vmLogScroll;
    private String lastVmLog;
    private TextView vmCpuValue, vmRamValue;
    private SparklineView vmCpuGraph, vmRamGraph;
    private VmRingView vmRing;
    private ExpandableLayout vmStatusExpand, vmLogsExpand;

    private View chrootCard;
    private TextView chrootBadge, chrootSpecs;
    private MaterialButton chrootMountBtn;
    private com.google.android.material.button.MaterialButtonToggleGroup engineToggleGroup;

    private final Handler vmHandler = new Handler(Looper.getMainLooper());
    private boolean vmRefreshing = false;
    private Runnable vmTick;
    private VmStatsCollector vmCollector;
    private TextView vmStatsChevron, vmStatsSummary, vmUsbChevron, vmUsbDetails;
    private ExpandableLayout vmStatsExpand, vmUsbExpand;
    private ExecutorService vmStatsExec;
    private final AtomicBoolean vmSampling = new AtomicBoolean(false);

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @SuppressLint({"SetTextI18n", "SdCardPath"})
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        TextView userHello = view.findViewById(R.id.user_hello);
        TextView userSubtitle = view.findViewById(R.id.user_subtitle);

        LinearLayout menuWifi = view.findViewById(R.id.menu_wifi);
        LinearLayout menuLocalNetwork = view.findViewById(R.id.menu_localnetwork);
        LinearLayout menuHs = view.findViewById(R.id.menu_hs);
        LinearLayout menuExploits = view.findViewById(R.id.menu_exloits);
        LinearLayout menuSploit = view.findViewById(R.id.menu_sploit);
        LinearLayout menuNuclei = view.findViewById(R.id.menu_nuclei);
        LinearLayout menuGeo = view.findViewById(R.id.menu_geo);
        LinearLayout menuMsf = view.findViewById(R.id.menu_msf);

        LinearLayout terminal = view.findViewById(R.id.terminal);
        LinearLayout news = view.findViewById(R.id.news);
        LinearLayout wifiHistory = view.findViewById(R.id.wifi_history);
        LinearLayout recentScan = view.findViewById(R.id.recent_scan);
        TextView savedCount = view.findViewById(R.id.saved_count);
        TextView recentScanCount = view.findViewById(R.id.recent_scan_count);
        TextView recentScanSubtitle = view.findViewById(R.id.recent_scan_subtitle);

        checkPermission();
        core.putInt("dashboard_open", core.getInt("dashboard_open") + 1);

        renderHero(userHello, userSubtitle, savedCount, recentScanCount, recentScanSubtitle);

        menuWifi.setOnClickListener(v -> receiver.changeFragment(R.id.wifi_item));
        menuLocalNetwork.setOnClickListener(v -> receiver.changeFragment(R.id.lan_item));
        menuHs.setOnClickListener(v -> receiver.changeFragment(R.id.hs_item));
        menuExploits.setOnClickListener(v -> receiver.changeFragment(
                R.id.arsenal_item, ArsenalFragment.forTab(ArsenalFragment.TAB_HUB)));
        menuNuclei.setOnClickListener(v -> receiver.changeFragment(R.id.nuclei_item));
        menuSploit.setOnClickListener(v -> receiver.changeFragment(
                R.id.arsenal_item, ArsenalFragment.forTab(ArsenalFragment.TAB_DB)));
        menuMsf.setOnClickListener(v -> receiver.changeFragment(R.id.metasploit_item));
        menuGeo.setOnClickListener(v -> receiver.changeFragment(R.id.geomac_item));

        terminal.setOnClickListener(v -> openTerminal());

        news.setOnClickListener(v -> receiver.changeFragment(R.id.dasboard_item, new NewsFragment(), "news"));
        wifiHistory.setOnClickListener(v -> receiver.changeFragment(R.id.dasboard_item, new WiFiHistoryFragment(), "wifi_history"));
        recentScan.setOnClickListener(v -> receiver.changeFragment(R.id.lan_item));

        MaterialCardView magiskNotification = view.findViewById(R.id.magisk);
        if (!core.isRootless()) {
            new Thread(() -> {
                if (core.checkMagiskNotification() && !core.getBoolean("magisk_notif")) {
                    activity.runOnUiThread(() -> magiskNotification.setVisibility(View.VISIBLE));
                }
            }).start();
        }

        MaterialButton magiskYes = view.findViewById(R.id.magisk_yes);
        MaterialButton magiskNo = view.findViewById(R.id.magisk_no);
        magiskYes.setOnClickListener(v -> {
            magiskNotification.setVisibility(View.GONE);
            core.disableMagiskNotification();
            if (!core.checkMagiskNotification()) {
                core.toaster(getString(R.string.magisk_off_success));
            } else {
                core.toaster(getString(R.string.magisk_notif_bad));
            }
            core.putBoolean("magisk_notif", true);
        });
        magiskNo.setOnClickListener(v -> {
            magiskNotification.setVisibility(View.GONE);
            core.putBoolean("magisk_notif", true);
        });

        if (!core.getBoolean("exploits_v30")) {
            new Thread(() -> {
                if (!core.checkFile(core.getShareRoot() + "/exploits/checker.py")) {
                    Snackbar s = Snackbar.make(activity.findViewById(android.R.id.content), "Updating please wait...", 60000);
                    activity.runOnUiThread(s::show);
                    com.zalexdev.stryker.engine.GuestCore.ensure(core);
                    core.customChrootCommand("mkdir -p /sdcard/Stryker/exploits; "
                            + "cp -f /exploits/* /sdcard/Stryker/exploits/ 2>/dev/null", true);
                    activity.runOnUiThread(s::dismiss);
                    core.putListString("installed_modules", new ArrayList<>());
                }
                core.putBoolean("exploits_v30", true);
            }).start();
        }

        setupEngineSelector(view);
        setupChrootCard(view);
        setupVmCard(view);

        showFirstScanTip(menuWifi);
    }


    private void setupVmCard(View view) {
        vmCard = view.findViewById(R.id.vm_card);
        if (vmCard == null) return;
        if (!core.vmInstalled()) {
            vmCard.setVisibility(View.GONE);
            return;
        }
        vmCard.setVisibility(View.VISIBLE);

        TextView cardTitle = view.findViewById(R.id.vm_card_title);
        vmBadge = view.findViewById(R.id.vm_status_badge);
        vmSpecs = view.findViewById(R.id.vm_specs_value);
        vmUsb = view.findViewById(R.id.vm_usb_value);
        vmStatusChevron = view.findViewById(R.id.vm_status_chevron);
        vmStatusExpand = view.findViewById(R.id.vm_status_expand);
        vmRing = view.findViewById(R.id.vm_ring);

        view.findViewById(R.id.vm_status_header).setOnClickListener(v -> {
            vmStatusExpand.toggle();
            vmStatusChevron.setText(vmStatusExpand.isExpanded() ? "▾" : "▸");
        });

        if (cardTitle != null) cardTitle.setText("Rootless VM");
        vmEngine = core.rootless();
        vmCollector = VmStatsCollector.get(context);
        vmCollector.start();

        vmStatsChevron = view.findViewById(R.id.vm_stats_chevron);
        vmStatsExpand = view.findViewById(R.id.vm_stats_expand);
        vmStatsSummary = view.findViewById(R.id.vm_stats_summary);
        vmCpuValue = view.findViewById(R.id.vm_cpu_stat_value);
        vmRamValue = view.findViewById(R.id.vm_ram_stat_value);
        vmCpuGraph = view.findViewById(R.id.vm_cpu_stat_graph);
        vmRamGraph = view.findViewById(R.id.vm_ram_stat_graph);

        vmUsbChevron = view.findViewById(R.id.vm_usb_chevron);
        vmUsbExpand = view.findViewById(R.id.vm_usb_expand);
        vmUsbDetails = view.findViewById(R.id.vm_usb_details);

        vmLogsChevron = view.findViewById(R.id.vm_logs_chevron);
        vmLogText = view.findViewById(R.id.vm_log_text);
        vmLogScroll = view.findViewById(R.id.vm_log_scroll);
        vmLogsExpand = view.findViewById(R.id.vm_logs_expand);
        if (vmLogScroll != null) {
            vmLogScroll.setOnTouchListener((v, event) -> {
                ViewParent parent = v.getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                return false;
            });
        }

        try {
            if (vmCpuGraph != null) {
                vmCpuGraph.setAccent(androidx.core.content.ContextCompat.getColor(context, R.color.accent_vm));
            }
            if (vmRamGraph != null) {
                vmRamGraph.setAccent(androidx.core.content.ContextCompat.getColor(context, R.color.green));
            }
        } catch (Exception ignored) {}
        vmStatsExec = Executors.newSingleThreadExecutor();

        section(view, R.id.vm_stats_header, vmStatsExpand, vmStatsChevron, this::sampleVmStats);
        section(view, R.id.vm_usb_header, vmUsbExpand, vmUsbChevron, this::refreshUsb);
        section(view, R.id.vm_logs_header, vmLogsExpand, vmLogsChevron, this::refreshVmLog);

        view.findViewById(R.id.vm_btn_start).setOnClickListener(v -> {
            RootlessService.start(context);
            if (vmCollector != null) vmCollector.start();
            refreshVmStatus();
        });
        view.findViewById(R.id.vm_btn_stop).setOnClickListener(v -> {
            RootlessService.stop(context);
            if (vmRing != null) {
                vmRing.setProgress(-1f);
                vmRing.setState(VmRingView.STATE_STOPPED);
            }
            if (vmBadge != null) vmBadge.setText("Stopping…");
            new Thread(() -> {
                try { vmEngine.stop(); } catch (Throwable ignored) {}
            }, "vm-stop").start();
        });
        view.findViewById(R.id.vm_btn_refresh_log).setOnClickListener(v -> refreshVmLog());

        vmTick = () -> {
            refreshVmStatus();
            sampleVmStats();
            if (vmLogsExpand != null && vmLogsExpand.isExpanded()) refreshVmLog();
            if (vmRefreshing) vmHandler.postDelayed(vmTick, 2500);
        };
        refreshVmStatus();
        refreshUsb();
    }

    private void section(View root, int headerId, ExpandableLayout expand, TextView chevron,
                         Runnable onExpand) {
        View header = root.findViewById(headerId);
        if (header == null || expand == null) return;
        header.setOnClickListener(v -> {
            expand.toggle();
            boolean open = expand.isExpanded();
            if (chevron != null) chevron.setText(open ? "▾" : "▸");
            if (open && onExpand != null) onExpand.run();
        });
    }

    private void sampleVmStats() {
        final VmStatsCollector collector = vmCollector;
        final RootlessEngine engine = vmEngine;
        ExecutorService exec = vmStatsExec;
        if (collector == null || engine == null || exec == null || exec.isShutdown()) return;
        if (!vmSampling.compareAndSet(false, true)) return;
        final boolean full = vmStatsExpand != null && vmStatsExpand.isExpanded();
        try {
            exec.execute(() -> {
                RootlessEngine.State state = RootlessEngine.State.STOPPED;
                int stage = -1;
                VmStatsCollector.Series series = null;
                try {
                    state = engine.statusBlocking();
                    if (state == RootlessEngine.State.BOOTING) {
                        stage = VmBootStage.detect(engine.tailLog(120));
                    }
                    series = collector.snapshot(full ? 160 : 8);
                } catch (Throwable ignored) {
                }
                final RootlessEngine.State finalState = state;
                final int finalStage = stage;
                final VmStatsCollector.Series finalSeries = series;
                Activity host = activity;
                if (host == null) {
                    vmSampling.set(false);
                    return;
                }
                host.runOnUiThread(() -> {
                    vmSampling.set(false);
                    renderRing(finalState, finalStage);
                    renderSeries(finalSeries, full);
                });
            });
        } catch (RejectedExecutionException e) {
            vmSampling.set(false);
        }
    }

    private void renderRing(RootlessEngine.State state, int stage) {
        if (vmRing == null) return;
        if (state == RootlessEngine.State.BOOTING) {
            vmRing.setState(VmRingView.STATE_BOOTING);
            vmRing.setProgress(stage >= 0 ? VmBootStage.fraction(stage) : -1f);
            if (vmBadge != null && stage >= 0 && context != null) {
                vmBadge.setText(context.getString(VmBootStage.labelRes(stage)));
            }
            return;
        }
        vmRing.setProgress(-1f);
        vmRing.setState(state == RootlessEngine.State.READY
                ? VmRingView.STATE_READY : VmRingView.STATE_STOPPED);
    }

    private void renderSeries(VmStatsCollector.Series s, boolean full) {
        if (s == null || context == null) return;
        String cpuText = s.lastCpu >= 0f
                ? String.format(Locale.ENGLISH, "%.0f%%", s.lastCpu)
                : unavailable(s);
        String ramText = s.lastRamMb >= 0 ? readableMb(s.lastRamMb) : unavailable(s);
        if (vmCpuValue != null) vmCpuValue.setText(cpuText);
        if (vmRamValue != null) vmRamValue.setText(ramText);
        if (vmStatsSummary != null) {
            vmStatsSummary.setText(s.lastCpu >= 0f || s.lastRamMb >= 0
                    ? cpuText + " · " + ramText : cpuText);
        }
        if (!full) return;
        if (vmCpuGraph != null) {
            float[] norm = new float[s.cpu.length];
            for (int i = 0; i < norm.length; i++) {
                norm[i] = s.cpu[i] < 0f ? -1f : s.cpu[i] / 100f;
            }
            vmCpuGraph.setValues(norm);
        }
        if (vmRamGraph != null) vmRamGraph.setValues(s.ramFraction);
    }

    private String unavailable(VmStatsCollector.Series s) {
        if (context == null) return "—";
        if (!s.running) return context.getString(R.string.vm_stats_offline);
        if (s.blocked) return context.getString(R.string.vm_stats_blocked);
        return context.getString(R.string.vm_stats_waiting);
    }

    private void refreshUsb() {
        if (vmUsb == null || context == null) return;
        final RootlessEngine engine = vmEngine;
        new Thread(() -> {
            final StringBuilder details = new StringBuilder();
            int attached = 0;
            int total = 0;
            try {
                android.hardware.usb.UsbManager um = (android.hardware.usb.UsbManager)
                        context.getSystemService(Context.USB_SERVICE);
                com.zalexdev.stryker.engine.UsbPassthroughManager usb =
                        engine == null ? null : engine.usb();
                if (um != null) {
                    for (android.hardware.usb.UsbDevice d : um.getDeviceList().values()) {
                        total++;
                        boolean live = usb != null && usb.isAttached(d);
                        if (live) attached++;
                        details.append(live ? "● " : "○ ").append(describeUsb(d));
                        details.append("  ").append(context.getString(live
                                ? R.string.vm_usb_attached_to_vm : R.string.vm_usb_host_only));
                        details.append('\n');
                    }
                }
            } catch (Throwable ignored) {
            }
            final int finalAttached = attached;
            final int finalTotal = total;
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> {
                if (vmUsb == null || context == null) return;
                String summary;
                if (finalTotal == 0) {
                    summary = context.getString(R.string.vm_usb_none);
                } else if (finalAttached > 0) {
                    summary = finalAttached + "/" + finalTotal + " · "
                            + context.getString(R.string.vm_usb_attached_to_vm);
                } else {
                    summary = finalTotal + " · " + context.getString(R.string.vm_usb_host_only);
                }
                vmUsb.setText(summary);
                if (vmUsbDetails != null) {
                    vmUsbDetails.setText(details.length() == 0
                            ? context.getString(R.string.vm_usb_none) : details.toString().trim());
                }
            });
        }, "vm-usb-scan").start();
    }

    private static String describeUsb(android.hardware.usb.UsbDevice d) {
        String name = null;
        try {
            name = d.getProductName();
        } catch (Throwable ignored) {
        }
        String ids = String.format(Locale.ENGLISH, "%04x:%04x", d.getVendorId(), d.getProductId());
        return name == null || name.trim().isEmpty() ? ids : name.trim() + " (" + ids + ")";
    }

    private static String readableMb(int mb) {
        if (mb >= 1024) return String.format(Locale.ENGLISH, "%.1f GB", mb / 1024f);
        return mb + " MB";
    }


    @SuppressLint("SetTextI18n")
    private void refreshVmStatus() {
        if (vmEngine == null || vmBadge == null) return;
        RootlessEngine.State st = vmEngine.status();
        String badge;
        int color, ring;
        switch (st) {
            case READY: {
                String prompt = vmEngine.guestPrompt();
                badge = prompt.isEmpty() ? "Ready" : "Ready · " + prompt;
                color = R.color.green;
                ring = VmRingView.STATE_READY;
                break;
            }
            case BOOTING:
                badge = "Booting…"; color = R.color.stryker_accent;
                ring = VmRingView.STATE_BOOTING; break;
            case STOPPED:
            default:
                badge = "Stopped"; color = R.color.grey;
                ring = VmRingView.STATE_STOPPED; break;
        }
        if (vmRing != null && st != RootlessEngine.State.BOOTING) {
            vmRing.setProgress(-1f);
            vmRing.setState(ring);
        }
        if (st != RootlessEngine.State.BOOTING) vmBadge.setText(badge);
        try { vmBadge.setTextColor(androidx.core.content.ContextCompat.getColor(context, color)); } catch (Exception ignored) {}

        int cpus = com.zalexdev.stryker.engine.VmSpecs.effectiveCpus(context, core);
        int ram = com.zalexdev.stryker.engine.VmSpecs.effectiveRamMb(context, core);
        String disk = com.zalexdev.stryker.engine.VmSpecs.humanBytes(
                com.zalexdev.stryker.engine.VmSpecs.diskAllocatedBytes(context));
        boolean kvm = com.zalexdev.stryker.engine.VmSpecs.kvmAvailable();
        vmSpecs.setText(cpus + " vCPU · " + ram + " MB · " + disk + " disk · " + (kvm ? "KVM" : "TCG"));
    }

    private void refreshVmLog() {
        if (vmEngine == null || vmLogText == null) return;
        new Thread(() -> {
            List<String> lines = vmEngine.tailLog(400);
            final StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            final String text = sb.length() == 0 ? "(no console output yet)" : sb.toString();
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> applyVmLog(text));
        }, "vm-log-tail").start();
    }

    private void applyVmLog(String text) {
        if (vmLogText == null || text == null) return;
        if (text.equals(lastVmLog)) return;
        boolean stick = isLogAtBottom();
        lastVmLog = text;
        vmLogText.setText(text);
        if (stick) scrollLogToBottom();
    }

    private boolean isLogAtBottom() {
        if (vmLogScroll == null || vmLogText == null) return true;
        int content = vmLogText.getHeight();
        if (content <= 0) return true;
        int viewport = vmLogScroll.getHeight();
        int slack = Math.round(24f * getResources().getDisplayMetrics().density);
        return content - (viewport + vmLogScroll.getScrollY()) <= slack;
    }

    private void scrollLogToBottom() {
        final android.widget.ScrollView scroll = vmLogScroll;
        final TextView text = vmLogText;
        if (scroll == null || text == null) return;
        scroll.post(() -> {
            if (vmLogScroll == null || vmLogText == null) return;
            int target = Math.max(0, vmLogText.getHeight() - vmLogScroll.getHeight());
            vmLogScroll.scrollTo(0, target);
        });
    }

    private void setupEngineSelector(View view) {
        final View selectorCard = view.findViewById(R.id.engine_selector_card);
        engineToggleGroup = view.findViewById(R.id.engine_toggle_group);
        if (selectorCard == null || engineToggleGroup == null) return;

        // Only offer the switch when both engines are usable; with a single engine the
        // selector is just noise. Availability probes run off-thread (root check spawns su).
        new Thread(() -> {
            final boolean chroot = EngineType.chrootAvailable(core);
            final boolean vm = core.vmInstalled();
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (chroot && vm) {
                    selectorCard.setVisibility(View.VISIBLE);
                    engineToggleGroup.check(core.isRootless()
                            ? R.id.btn_engine_vm : R.id.btn_engine_chroot);
                    engineToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                        if (!isChecked) return;
                        EngineType active = checkedId == R.id.btn_engine_vm
                                ? EngineType.ROOTLESS : EngineType.CHROOT;
                        EngineType.persist(core, active);
                        if (active == EngineType.ROOTLESS) {
                            RootlessService.start(context);
                            refreshVmStatus();
                        } else {
                            new Thread(() -> {
                                if (!core.isMounted()) core.mountCore();
                            }, "stryker-chroot-ondemand").start();
                        }
                        core.toaster("Active engine: "
                                + (active == EngineType.ROOTLESS ? "Rootless VM" : "Chroot (root)"));
                    });
                } else {
                    selectorCard.setVisibility(View.GONE);
                }
            });
        }, "stryker-engine-selector").start();
    }

    private void setupChrootCard(View view) {
        chrootCard = view.findViewById(R.id.chroot_card);
        if (chrootCard == null) return;
        chrootBadge = view.findViewById(R.id.chroot_status_badge);
        chrootSpecs = view.findViewById(R.id.chroot_specs_value);
        chrootMountBtn = view.findViewById(R.id.chroot_btn_mount);
        if (chrootMountBtn != null) chrootMountBtn.setOnClickListener(v -> toggleChrootMount());
        refreshChrootCard();
    }

    @SuppressLint("SetTextI18n")
    private void refreshChrootCard() {
        if (chrootCard == null) return;
        new Thread(() -> {
            final boolean available = EngineType.chrootAvailable(core);
            final boolean mounted = available && core.isMounted();
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> {
                if (!isAdded() || chrootCard == null) return;
                if (!available) {
                    chrootCard.setVisibility(View.GONE);
                    return;
                }
                chrootCard.setVisibility(View.VISIBLE);
                if (chrootBadge != null) {
                    chrootBadge.setText(mounted ? "Mounted" : "Detached");
                    chrootBadge.setTextColor(androidx.core.content.ContextCompat.getColor(
                            context, mounted ? R.color.green : R.color.grey));
                }
                if (chrootSpecs != null) {
                    chrootSpecs.setText(mounted
                            ? "Debian toolset at " + Core.CHROOT_ROOT
                            : "Not mounted — tap below to attach");
                }
                if (chrootMountBtn != null) {
                    chrootMountBtn.setText(mounted ? "Unmount" : "Mount");
                    chrootMountBtn.setEnabled(true);
                }
            });
        }, "stryker-chroot-card").start();
    }

    private void toggleChrootMount() {
        if (chrootMountBtn != null) chrootMountBtn.setEnabled(false);
        new Thread(() -> {
            if (core.isMounted()) core.unmountCore();
            else core.mountCore();
            refreshChrootCard();
        }, "stryker-chroot-toggle").start();
    }

    private void hide(View root, int... ids) {
        for (int id : ids) {
            View v = root.findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (vmEngine != null && vmTick != null) {
            vmRefreshing = true;
            vmHandler.post(vmTick);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        vmRefreshing = false;
        vmHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroyView() {
        vmRefreshing = false;
        vmHandler.removeCallbacksAndMessages(null);
        if (vmStatsExec != null) {
            vmStatsExec.shutdownNow();
            vmStatsExec = null;
        }
        vmSampling.set(false);
        vmCollector = null;
        vmStatsExpand = null;
        vmUsbExpand = null;
        vmStatsSummary = null;
        vmUsbDetails = null;
        vmLogScroll = null;
        lastVmLog = null;
        vmRing = null;
        vmCpuGraph = null;
        vmRamGraph = null;
        vmCpuValue = null;
        vmRamValue = null;
        super.onDestroyView();
    }

    private void renderHero(TextView hello, TextView subtitle, TextView savedCount, TextView recentScanCount, TextView recentScanSubtitle) {
        String username = core.getString("username");
        if (username == null || username.isEmpty() || username.equals("User")) {
            hello.setText(getString(R.string.dashboard_hello));
        } else {
            hello.setText(getString(R.string.dashboard_hello) + ", " + username);
        }

        int saved = core.getSavedNetworks().size();
        int lastScan = core.getLastNetworkScan().size();

        if (saved == 0 && lastScan == 0) {
            subtitle.setText(R.string.dashboard_subtitle_default);
        } else {
            subtitle.setText(getString(R.string.dashboard_subtitle_stats, saved, lastScan));
        }

        savedCount.setText(String.valueOf(saved));
        if (lastScan > 0) {
            recentScanCount.setVisibility(View.VISIBLE);
            recentScanCount.setText(String.valueOf(lastScan));
            recentScanSubtitle.setText(getString(R.string.dashboard_card_recentscan_subtitle));
        } else {
            recentScanCount.setVisibility(View.GONE);
            recentScanSubtitle.setText(getString(R.string.dashboard_card_recentscan_empty));
        }
    }

    private void openTerminal() {
        if (!core.isRootless()) {
            launchTerminal();
            return;
        }
        if (core.rootless().status() == RootlessEngine.State.READY) {
            launchTerminal();
            return;
        }
        // status() is non-blocking and goes stale on purpose, so confirm with a real probe before
        // refusing — otherwise a healthy VM gets a "start the VM first" toast.
        new Thread(() -> {
            boolean ready = core.rootless().statusBlocking() == RootlessEngine.State.READY;
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (ready) launchTerminal();
                else core.toaster(getString(R.string.vm_required_terminal));
            });
        }, "terminal-vm-check").start();
    }

    private void launchTerminal() {
        Intent terminal = new Intent(context, com.stryker.terminal.ui.term.NeoTermActivity.class);
        terminal.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        terminal.putExtra(com.stryker.terminal.ui.term.NeoTermActivity.EXTRA_NEW_SESSION, true);
        context.startActivity(terminal);
    }

    private void showFirstScanTip(View target) {
        if (core.getInt("dashboard_open") == 12 && !core.getBoolean("firstscan")) {
            TapTargetView.showFor(activity,
                    TapTarget.forView(target, "Tip: Networks with ⭐",
                                    "Networks with ⭐ are likely vulnerable to Pixie Dust")
                            .outerCircleColor(R.color.stryker_accent)
                            .outerCircleAlpha(0.96f)
                            .targetCircleColor(android.R.color.white)
                            .titleTextSize(20)
                            .titleTextColor(android.R.color.white)
                            .descriptionTextSize(16)
                            .descriptionTextColor(android.R.color.white)
                            .textColor(android.R.color.white)
                            .dimColor(android.R.color.black)
                            .drawShadow(true)
                            .cancelable(true)
                            .tintTarget(true)
                            .transparentTarget(true)
                            .targetRadius(60));
            core.putBoolean("firstscan", true);
        }
    }

    private void checkPermission() {
        if (context.checkSelfPermission(WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{WRITE_EXTERNAL_STORAGE},
                    123
            );
        }
    }
}
