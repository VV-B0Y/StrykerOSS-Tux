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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
import com.zalexdev.stryker.engine.QemuInstaller;
import com.zalexdev.stryker.engine.RootlessEngine;
import com.zalexdev.stryker.engine.RootlessPaths;
import com.zalexdev.stryker.engine.RootlessService;
import com.zalexdev.stryker.engine.VmBootStage;
import com.zalexdev.stryker.engine.VmRegistry;
import com.zalexdev.stryker.engine.VmSpecs;
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

    private LinearLayout vmListContainer;
    private MaterialButton vmAddBtn;
    private final List<VmCard> vmCards = new ArrayList<>();
    private VmStatsCollector vmCollector;
    private ExecutorService vmStatsExec;
    private final AtomicBoolean vmSampling = new AtomicBoolean(false);
    private final Handler vmHandler = new Handler(Looper.getMainLooper());
    private boolean vmRefreshing = false;
    private Runnable vmTick;
    private AlertDialog progressDialog;
    private TextView progressText;

    private View chrootCard;
    private TextView chrootBadge, chrootSpecs;
    private MaterialButton chrootMountBtn;
    private com.google.android.material.button.MaterialButtonToggleGroup engineToggleGroup;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        // The terminal module detects rootless mode by a flag file (the app's SharedPreferences are
        // encrypted, so the terminal can't read them); that flag is only written on an explicit engine
        // switch. Rewrite it here so a fresh launch keeps the terminal pointing at the guest.
        if (core.isRootless()) EngineType.persist(core, EngineType.ROOTLESS);
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
        setupVmCards(view);

        showFirstScanTip(menuWifi);
    }


    private void setupVmCards(View view) {
        vmListContainer = view.findViewById(R.id.vm_list_container);
        vmAddBtn = view.findViewById(R.id.vm_btn_add);
        if (vmListContainer == null) return;
        if (!core.vmInstalled()) {
            vmListContainer.setVisibility(View.GONE);
            if (vmAddBtn != null) vmAddBtn.setVisibility(View.GONE);
            return;
        }
        vmCollector = VmStatsCollector.get(context);
        vmCollector.start();
        vmStatsExec = Executors.newSingleThreadExecutor();

        if (vmAddBtn != null) {
            vmAddBtn.setOnClickListener(v -> showAddVmDialog(null));
        }

        renderVmCards();

        vmTick = () -> {
            for (VmCard c : vmCards) {
                if (c == null) continue;
                refreshVmStatus(c);
                if (c.primary) sampleVmStats(c);
                if (c.logsExpand != null && c.logsExpand.isExpanded()) refreshVmLog(c);
            }
            if (vmRefreshing) vmHandler.postDelayed(vmTick, 2500);
        };
        if (isResumed()) {
            vmRefreshing = true;
            vmHandler.post(vmTick);
        }
    }

    private void renderVmCards() {
        if (vmListContainer == null) return;
        vmListContainer.removeAllViews();
        vmCards.clear();
        VmRegistry registry = VmRegistry.get(context);
        List<VmRegistry.VmInfo> infos = registry.list();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (VmRegistry.VmInfo info : infos) {
            View card = inflater.inflate(R.layout.item_vm_card, vmListContainer, false);
            VmCard holder = bindVmCard(card, info);
            vmCards.add(holder);
            vmListContainer.addView(card);
        }
        if (vmAddBtn != null) {
            vmAddBtn.setVisibility(registry.atCapacity() ? View.GONE : View.VISIBLE);
        }
    }

    private VmCard bindVmCard(View root, VmRegistry.VmInfo info) {
        final VmCard c = new VmCard(info, core.vm(info.id), root, info.index == 0);

        if (c.title != null) c.title.setText(info.name);
        if (c.usbValue != null) c.usbValue.setText(R.string.vm_usb_none);
        if (c.usbDetails != null) c.usbDetails.setText(R.string.vm_usb_none);
        if (c.logText != null) c.logText.setText("(no console output yet)");
        if (c.cpuValue != null) c.cpuValue.setText(R.string.vm_stat_placeholder);
        if (c.ramValue != null) c.ramValue.setText(R.string.vm_stat_placeholder);

        section(root, R.id.vm_status_header, c.statusExpand, c.statusChevron, null);
        section(root, R.id.vm_stats_header, c.statsExpand, c.statsChevron, () -> sampleVmStats(c));
        section(root, R.id.vm_usb_header, c.usbExpand, c.usbChevron, () -> refreshUsb(c));
        section(root, R.id.vm_logs_header, c.logsExpand, c.logsChevron, () -> refreshVmLog(c));

        if (c.logScroll != null) {
            c.logScroll.setOnTouchListener((v, event) -> {
                ViewParent parent = v.getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                return false;
            });
        }

        try {
            if (c.cpuGraph != null) {
                c.cpuGraph.setAccent(androidx.core.content.ContextCompat.getColor(context, R.color.accent_vm));
            }
            if (c.ramGraph != null) {
                c.ramGraph.setAccent(androidx.core.content.ContextCompat.getColor(context, R.color.green));
            }
        } catch (Exception ignored) {}

        if (c.startBtn != null) c.startBtn.setOnClickListener(v -> startVm(c));
        if (c.stopBtn != null) c.stopBtn.setOnClickListener(v -> stopVm(c));
        if (root.findViewById(R.id.vm_btn_refresh_log) != null) {
            root.findViewById(R.id.vm_btn_refresh_log).setOnClickListener(v -> refreshVmLog(c));
        }
        if (c.moreBtn != null) c.moreBtn.setOnClickListener(v -> showVmMenu(c));

        // SIMPLIFICATION: the CPU/RAM sparkline stats and the USB device list are backed by
        // vm0-oriented machinery today (VmStatsCollector samples a single qemu process, and
        // USB attach is single-VM), so they are kept only on the primary card (index 0).
        // Additional cards still get name, status badge, specs, start/stop and a per-VM console log.
        if (!c.primary) {
            View divider = root.findViewById(R.id.vm_console_divider);
            if (divider != null) divider.setVisibility(View.GONE);
            View statsHeader = root.findViewById(R.id.vm_stats_header);
            if (statsHeader != null) statsHeader.setVisibility(View.GONE);
            if (c.statsExpand != null) c.statsExpand.setVisibility(View.GONE);
            View usbDivider = root.findViewById(R.id.vm_divider_usb);
            if (usbDivider != null) usbDivider.setVisibility(View.GONE);
            View usbHeader = root.findViewById(R.id.vm_usb_header);
            if (usbHeader != null) usbHeader.setVisibility(View.GONE);
            if (c.usbExpand != null) c.usbExpand.setVisibility(View.GONE);
        }

        refreshVmStatus(c);
        if (c.primary) refreshUsb(c);
        return c;
    }

    private void startVm(final VmCard c) {
        if (c.engine == null) return;
        if (c.badge != null) {
            c.badge.setText(R.string.vm_starting);
            c.badge.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.stryker_accent));
        }
        if (c.ring != null) {
            c.ring.setState(VmRingView.STATE_BOOTING);
            c.ring.setProgress(-1f);
        }
        synchronized (c.bootLines) { c.bootLines.clear(); }
        new Thread(() -> {
            c.engine.startBlocking(new RootlessEngine.BootListener() {
                @Override public void onBootLine(String line) {
                    synchronized (c.bootLines) {
                        c.bootLines.add(line);
                        if (c.bootLines.size() > 160) c.bootLines.subList(0, c.bootLines.size() - 160).clear();
                    }
                    long now = System.currentTimeMillis();
                    if (now - c.lastBootPost < 600) return;
                    c.lastBootPost = now;
                    List<String> snapshot;
                    synchronized (c.bootLines) { snapshot = new ArrayList<>(c.bootLines); }
                    Activity host = activity;
                    if (host != null) host.runOnUiThread(() -> applyBootStage(c, snapshot));
                }
                @Override public void onBooted() {
                    Activity host = activity;
                    if (host != null) host.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        refreshVmStatus(c);
                    });
                }
                @Override public void onFailed(String reason) {
                    Activity host = activity;
                    if (host != null) host.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (c.badge != null) c.badge.setText(R.string.vm_boot_failed);
                        refreshVmStatus(c);
                    });
                }
            });
        }, "vm-start-" + c.info.id).start();
    }

    private void stopVm(final VmCard c) {
        if (c.engine == null) return;
        if (c.badge != null) {
            c.badge.setText(R.string.vm_stopping);
            c.badge.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.grey));
        }
        if (c.ring != null) {
            c.ring.setState(VmRingView.STATE_STOPPED);
            c.ring.setProgress(-1f);
        }
        new Thread(() -> {
            try { c.engine.stop(); } catch (Throwable ignored) {}
            Activity host = activity;
            if (host != null) host.runOnUiThread(() -> refreshVmStatus(c));
        }, "vm-stop-" + c.info.id).start();
    }

    private void applyBootStage(VmCard c, List<String> lines) {
        if (!isAdded()) return;
        int stage = VmBootStage.detect(lines);
        if (c.ring != null) {
            c.ring.setState(VmRingView.STATE_BOOTING);
            c.ring.setProgress(stage >= 0 ? VmBootStage.fraction(stage) : -1f);
        }
        if (c.badge != null && stage > VmBootStage.START && stage < VmBootStage.READY) {
            c.badge.setText(getString(VmBootStage.labelRes(stage)));
        }
    }

    @SuppressLint("SetTextI18n")
    private void refreshVmStatus(VmCard c) {
        if (c == null || c.engine == null || c.badge == null) return;

        if (c.specs != null) {
            int cpus = VmSpecs.effectiveCpus(context, core, c.info.index);
            int ram = VmSpecs.effectiveRamMb(context, core, c.info.index);
            long diskBytes = RootlessPaths.rootfs(context, c.info.id).length();
            String disk = VmSpecs.humanBytes(diskBytes);
            boolean kvm = VmSpecs.kvmAvailable();
            c.specs.setText(cpus + " vCPU · " + ram + " MB · " + disk + " disk · " + (kvm ? "KVM" : "TCG"));
        }

        // Apply the non-blocking status immediately, then refresh it with a real ping.
        // Non-blocking status() reads lastGuestOk, which goes stale 15s after the last ping —
        // and only the primary card is sampled — so a running secondary VM would otherwise
        // read "Booting" forever.
        applyVmStatus(c, c.engine.status());
        final RootlessEngine engine = c.engine;
        final VmCard card = c;
        final ExecutorService exec = vmStatsExec;
        if (exec == null || exec.isShutdown()) return;
        try {
            exec.execute(() -> {
                RootlessEngine.State st = engine.statusBlocking();
                Activity host = activity;
                if (host == null) return;
                host.runOnUiThread(() -> applyVmStatus(card, st));
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void applyVmStatus(VmCard c, RootlessEngine.State st) {
        if (c == null || c.badge == null) return;
        String badge;
        int color, ring;
        switch (st) {
            case READY: {
                String prompt = c.engine == null ? null : c.engine.guestPrompt();
                badge = prompt == null || prompt.isEmpty() ? "Ready" : "Ready · " + prompt;
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
        if (c.ring != null && st != RootlessEngine.State.BOOTING) {
            c.ring.setProgress(-1f);
            c.ring.setState(ring);
        }
        if (st != RootlessEngine.State.BOOTING) c.badge.setText(badge);
        try {
            c.badge.setTextColor(androidx.core.content.ContextCompat.getColor(context, color));
        } catch (Exception ignored) {}
    }

    private void refreshAllVmStatus() {
        for (VmCard c : vmCards) refreshVmStatus(c);
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

    private void sampleVmStats(final VmCard c) {
        final VmStatsCollector collector = vmCollector;
        final RootlessEngine engine = c == null ? null : c.engine;
        ExecutorService exec = vmStatsExec;
        if (collector == null || engine == null || exec == null || exec.isShutdown()) return;
        if (!vmSampling.compareAndSet(false, true)) return;
        final boolean full = c.statsExpand != null && c.statsExpand.isExpanded();
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
                    renderRing(c, finalState, finalStage);
                    renderSeries(c, finalSeries, full);
                });
            });
        } catch (RejectedExecutionException e) {
            vmSampling.set(false);
        }
    }

    private void renderRing(VmCard c, RootlessEngine.State state, int stage) {
        if (c == null || c.ring == null) return;
        if (state == RootlessEngine.State.BOOTING) {
            c.ring.setState(VmRingView.STATE_BOOTING);
            c.ring.setProgress(stage >= 0 ? VmBootStage.fraction(stage) : -1f);
            if (c.badge != null && stage >= 0 && context != null) {
                c.badge.setText(context.getString(VmBootStage.labelRes(stage)));
            }
            return;
        }
        c.ring.setProgress(-1f);
        c.ring.setState(state == RootlessEngine.State.READY
                ? VmRingView.STATE_READY : VmRingView.STATE_STOPPED);
    }

    private void renderSeries(VmCard c, VmStatsCollector.Series s, boolean full) {
        if (c == null || s == null || context == null) return;
        String cpuText = s.lastCpu >= 0f
                ? String.format(Locale.ENGLISH, "%.0f%%", s.lastCpu)
                : unavailable(s);
        String ramText = s.lastRamMb >= 0 ? readableMb(s.lastRamMb) : unavailable(s);
        if (c.cpuValue != null) c.cpuValue.setText(cpuText);
        if (c.ramValue != null) c.ramValue.setText(ramText);
        if (c.statsSummary != null) {
            c.statsSummary.setText(s.lastCpu >= 0f || s.lastRamMb >= 0
                    ? cpuText + " · " + ramText : cpuText);
        }
        if (!full) return;
        if (c.cpuGraph != null) {
            float[] norm = new float[s.cpu.length];
            for (int i = 0; i < norm.length; i++) {
                norm[i] = s.cpu[i] < 0f ? -1f : s.cpu[i] / 100f;
            }
            c.cpuGraph.setValues(norm);
        }
        if (c.ramGraph != null) c.ramGraph.setValues(s.ramFraction);
    }

    private String unavailable(VmStatsCollector.Series s) {
        if (context == null) return "—";
        if (!s.running) return context.getString(R.string.vm_stats_offline);
        if (s.blocked) return context.getString(R.string.vm_stats_blocked);
        return context.getString(R.string.vm_stats_waiting);
    }

    private void refreshUsb(final VmCard c) {
        if (c == null || c.usbValue == null || context == null) return;
        final RootlessEngine engine = c.engine;
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
                if (c.usbValue == null || context == null) return;
                String summary;
                if (finalTotal == 0) {
                    summary = context.getString(R.string.vm_usb_none);
                } else if (finalAttached > 0) {
                    summary = finalAttached + "/" + finalTotal + " · "
                            + context.getString(R.string.vm_usb_attached_to_vm);
                } else {
                    summary = finalTotal + " · " + context.getString(R.string.vm_usb_host_only);
                }
                c.usbValue.setText(summary);
                if (c.usbDetails != null) {
                    c.usbDetails.setText(details.length() == 0
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

    private void refreshVmLog(final VmCard c) {
        if (c == null || c.engine == null || c.logText == null) return;
        new Thread(() -> {
            List<String> lines = c.engine.tailLog(400);
            final StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            final String text = sb.length() == 0 ? "(no console output yet)" : sb.toString();
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> applyVmLog(c, text));
        }, "vm-log-tail").start();
    }

    private void applyVmLog(VmCard c, String text) {
        if (c == null || c.logText == null || text == null) return;
        if (text.equals(c.lastLog)) return;
        boolean stick = isLogAtBottom(c);
        c.lastLog = text;
        c.logText.setText(text);
        if (stick) scrollLogToBottom(c);
    }

    private boolean isLogAtBottom(VmCard c) {
        if (c == null || c.logScroll == null || c.logText == null) return true;
        int content = c.logText.getHeight();
        if (content <= 0) return true;
        int viewport = c.logScroll.getHeight();
        int slack = Math.round(24f * getResources().getDisplayMetrics().density);
        return content - (viewport + c.logScroll.getScrollY()) <= slack;
    }

    private void scrollLogToBottom(final VmCard c) {
        final android.widget.ScrollView scroll = c == null ? null : c.logScroll;
        final TextView text = c == null ? null : c.logText;
        if (scroll == null || text == null) return;
        scroll.post(() -> {
            if (c.logScroll == null || c.logText == null) return;
            int target = Math.max(0, c.logText.getHeight() - c.logScroll.getHeight());
            c.logScroll.scrollTo(0, target);
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
                            refreshAllVmStatus();
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
        if (!vmCards.isEmpty() && vmTick != null) {
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
        dismissProgress();
        vmCards.clear();
        vmListContainer = null;
        vmAddBtn = null;
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

    /** Per-VM card holder: binds one inflated item_vm_card to its VmInfo + RootlessEngine. */
    private static final class VmCard {
        final VmRegistry.VmInfo info;
        final RootlessEngine engine;
        final View root;
        final boolean primary;
        final TextView title, badge, specs, usbValue, usbDetails;
        final TextView statusChevron, statsChevron, usbChevron, logsChevron;
        final TextView statsSummary, cpuValue, ramValue, logText;
        final SparklineView cpuGraph, ramGraph;
        final VmRingView ring;
        final ExpandableLayout statusExpand, statsExpand, usbExpand, logsExpand;
        final android.widget.ScrollView logScroll;
        final MaterialButton startBtn, stopBtn;
        final TextView moreBtn;
        String lastLog = "";
        final List<String> bootLines = new ArrayList<>();
        long lastBootPost = 0L;

        VmCard(VmRegistry.VmInfo info, RootlessEngine engine, View root, boolean primary) {
            this.info = info;
            this.engine = engine;
            this.root = root;
            this.primary = primary;
            this.title = root.findViewById(R.id.vm_card_title);
            this.badge = root.findViewById(R.id.vm_status_badge);
            this.specs = root.findViewById(R.id.vm_specs_value);
            this.usbValue = root.findViewById(R.id.vm_usb_value);
            this.usbDetails = root.findViewById(R.id.vm_usb_details);
            this.statusChevron = root.findViewById(R.id.vm_status_chevron);
            this.statsChevron = root.findViewById(R.id.vm_stats_chevron);
            this.usbChevron = root.findViewById(R.id.vm_usb_chevron);
            this.logsChevron = root.findViewById(R.id.vm_logs_chevron);
            this.statsSummary = root.findViewById(R.id.vm_stats_summary);
            this.cpuValue = root.findViewById(R.id.vm_cpu_stat_value);
            this.ramValue = root.findViewById(R.id.vm_ram_stat_value);
            this.logText = root.findViewById(R.id.vm_log_text);
            this.cpuGraph = root.findViewById(R.id.vm_cpu_stat_graph);
            this.ramGraph = root.findViewById(R.id.vm_ram_stat_graph);
            this.ring = root.findViewById(R.id.vm_ring);
            this.statusExpand = root.findViewById(R.id.vm_status_expand);
            this.statsExpand = root.findViewById(R.id.vm_stats_expand);
            this.usbExpand = root.findViewById(R.id.vm_usb_expand);
            this.logsExpand = root.findViewById(R.id.vm_logs_expand);
            this.logScroll = root.findViewById(R.id.vm_log_scroll);
            this.startBtn = root.findViewById(R.id.vm_btn_start);
            this.stopBtn = root.findViewById(R.id.vm_btn_stop);
            this.moreBtn = root.findViewById(R.id.vm_btn_more);
        }
    }

    private void showAddVmDialog(String defaultSourceId) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vm_add_title)
                .setItems(new String[]{
                        getString(R.string.vm_add_clone),
                        getString(R.string.vm_add_helper),
                        getString(R.string.vm_add_new)
                }, (d, which) -> {
                    if (which == 0) showCloneDialog(defaultSourceId);
                    else if (which == 1) showHelperDialog();
                    else showNewVmDialog();
                })
                .show();
    }

    private void showCloneDialog(String defaultSourceId) {
        final List<VmRegistry.VmInfo> infos = VmRegistry.get(context).list();
        if (infos.isEmpty()) return;

        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16f * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);

        TextView srcLabel = new TextView(context);
        srcLabel.setText(R.string.vm_source_label);
        srcLabel.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.grey));
        srcLabel.setTextSize(12f);
        form.addView(srcLabel);

        final Spinner source = new Spinner(context);
        List<String> names = new ArrayList<>();
        for (VmRegistry.VmInfo i : infos) names.add(i.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item, names);
        source.setAdapter(adapter);
        int sel = 0;
        for (int i = 0; i < infos.size(); i++) {
            if (infos.get(i).id.equals(defaultSourceId)) { sel = i; break; }
        }
        source.setSelection(sel);
        form.addView(source);

        final EditText nameInput = new EditText(context);
        nameInput.setHint(R.string.vm_name_hint);
        nameInput.setSingleLine(true);
        form.addView(nameInput);

        final EditText sizeInput = new EditText(context);
        sizeInput.setHint(R.string.vm_disk_size_hint);
        sizeInput.setSingleLine(true);
        sizeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(sizeInput);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vm_clone_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    VmRegistry.VmInfo src = infos.get(source.getSelectedItemPosition());
                    String name = nameInput.getText().toString().trim();
                    long sizeBytes = 0;
                    String sizeRaw = sizeInput.getText().toString().trim();
                    if (!sizeRaw.isEmpty()) {
                        try {
                            double gb = Double.parseDouble(sizeRaw);
                            if (gb <= 0) throw new NumberFormatException();
                            sizeBytes = (long) (gb * 1024L * 1024L * 1024L);
                        } catch (NumberFormatException e) {
                            core.toaster(getString(R.string.vm_disk_size_invalid));
                            return;
                        }
                    }
                    startClone(src.id, name, sizeBytes);
                })
                .show();
    }

    private void showNewVmDialog() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16f * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);

        final EditText nameInput = new EditText(context);
        nameInput.setHint(R.string.vm_name_hint);
        nameInput.setSingleLine(true);
        form.addView(nameInput);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vm_new_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        startNewVm(nameInput.getText().toString().trim()))
                .show();
    }

    private void showHelperDialog() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16f * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);

        final EditText nameInput = new EditText(context);
        nameInput.setHint(R.string.vm_name_hint);
        nameInput.setSingleLine(true);
        form.addView(nameInput);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vm_helper_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        startHelper(nameInput.getText().toString().trim()))
                .show();
    }

    private void startHelper(final String name) {
        final VmRegistry.VmInfo info;
        try {
            info = VmRegistry.get(context).clone("vm0", name, 0);
        } catch (Exception e) {
            core.toaster(getString(R.string.vm_error_clone) + ": " + e.getMessage());
            return;
        }
        showProgress(getString(R.string.vm_progress_helper));
        final String id = info.id;
        new Thread(() -> {
            final boolean ok = QemuInstaller.provisionHelper(context, id, new QemuInstaller.Progress() {
                @Override public void onStage(QemuInstaller.Stage stage) {
                    updateProgress(stage.title);
                }
                @Override public void onBytes(String label, long done) {
                    updateProgress(label + " · " + VmSpecs.humanBytes(done));
                }
                @Override public void onLog(int level, String message) {
                    updateProgress(message);
                }
            });
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> {
                dismissProgress();
                if (ok) {
                    renderVmCards();
                    core.toaster(getString(R.string.vm_helper_done));
                } else {
                    VmRegistry.get(context).remove(id, true);
                    renderVmCards();
                    core.toaster(getString(R.string.vm_helper_failed));
                }
            });
        }, "vm-helper").start();
    }

    private void startClone(final String srcId, final String name, final long sizeBytes) {
        showProgress(getString(R.string.vm_progress_cloning));
        new Thread(() -> {
            try {
                VmRegistry.get(context).clone(srcId, name, sizeBytes);
                Activity host = activity;
                if (host != null) host.runOnUiThread(() -> {
                    dismissProgress();
                    renderVmCards();
                    core.toaster(getString(R.string.vm_created));
                });
            } catch (Throwable t) {
                Activity host = activity;
                if (host != null) host.runOnUiThread(() -> {
                    dismissProgress();
                    core.toaster(getString(R.string.vm_error_clone) + ": " + t.getMessage());
                });
            }
        }, "vm-clone").start();
    }

    private void startNewVm(final String name) {
        final VmRegistry.VmInfo info;
        try {
            info = VmRegistry.get(context).create(name);
        } catch (IllegalStateException e) {
            core.toaster(getString(R.string.vm_error_capacity));
            return;
        }
        showProgress(getString(R.string.vm_progress_installing));
        final String id = info.id;
        new Thread(() -> {
            final boolean ok = QemuInstaller.installRootfsForVm(context, id, new QemuInstaller.Progress() {
                @Override public void onStage(QemuInstaller.Stage stage) {
                    updateProgress(stage.title);
                }
                @Override public void onBytes(String label, long done) {
                    updateProgress(label + " · " + VmSpecs.humanBytes(done));
                }
                @Override public void onLog(int level, String message) {
                    updateProgress(message);
                }
            });
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> {
                dismissProgress();
                if (ok) {
                    renderVmCards();
                    core.toaster(getString(R.string.vm_created));
                } else {
                    VmRegistry.get(context).remove(id, true);
                    renderVmCards();
                    core.toaster(getString(R.string.vm_error_install));
                }
            });
        }, "vm-install").start();
    }

    private void showVmMenu(final VmCard c) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(c.info.name)
                .setItems(new String[]{
                        getString(R.string.vm_menu_rename),
                        getString(R.string.vm_menu_clone),
                        getString(R.string.vm_menu_delete)
                }, (d, which) -> {
                    if (which == 0) showRenameDialog(c);
                    else if (which == 1) showAddVmDialog(c.info.id);
                    else confirmDeleteVm(c);
                })
                .show();
    }

    private void showRenameDialog(final VmCard c) {
        final EditText input = new EditText(context);
        input.setText(c.info.name);
        input.setSingleLine(true);
        input.setSelection(input.length());
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vm_rename_title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    VmRegistry.get(context).rename(c.info.id, name);
                    if (c.title != null) c.title.setText(c.info.name);
                })
                .show();
    }

    private void confirmDeleteVm(final VmCard c) {
        final VmRegistry reg = VmRegistry.get(context);
        if (reg.count() <= 1) {
            core.toaster(getString(R.string.vm_delete_last));
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vm_delete_title)
                .setMessage(getString(R.string.vm_delete_message, c.info.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    showProgress(getString(R.string.vm_stopping));
                    new Thread(() -> {
                        try { c.engine.stop(); } catch (Throwable ignored) {}
                        Activity host = activity;
                        if (host == null) return;
                        host.runOnUiThread(() -> {
                            dismissProgress();
                            reg.remove(c.info.id, true);
                            renderVmCards();
                        });
                    }, "vm-delete").start();
                })
                .show();
    }

    private void showProgress(String message) {
        dismissProgress();
        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = Math.round(20f * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad, pad, pad);
        ProgressBar pb = new ProgressBar(context);
        ll.addView(pb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        progressText = new TextView(context);
        progressText.setText(message);
        progressText.setTextSize(14f);
        progressText.setPadding(pad, 0, 0, 0);
        ll.addView(progressText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        progressDialog = new MaterialAlertDialogBuilder(context)
                .setCancelable(false)
                .setView(ll)
                .show();
    }

    private void updateProgress(String message) {
        Activity host = activity;
        if (host == null) return;
        host.runOnUiThread(() -> {
            if (progressText != null) progressText.setText(message);
        });
    }

    private void dismissProgress() {
        if (progressDialog != null && progressDialog.isShowing()) {
            try { progressDialog.dismiss(); } catch (Throwable ignored) {}
        }
        progressDialog = null;
        progressText = null;
    }
}
