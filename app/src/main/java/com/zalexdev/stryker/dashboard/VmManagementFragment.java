package com.zalexdev.stryker.dashboard;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.engine.RootlessEngine;
import com.zalexdev.stryker.engine.RootlessPaths;
import com.zalexdev.stryker.engine.VmRegistry;
import com.zalexdev.stryker.engine.VmSpecs;
import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.List;

/** Enterprise-solid VM management: list every VM with live status, specs, and per-VM actions. */
public class VmManagementFragment extends Fragment {

    private Core core;
    private VmRegistry reg;
    private VmAdapter adapter;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_vm_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        core = new Core(requireContext());
        reg = VmRegistry.get(requireContext());

        RecyclerView recycler = view.findViewById(R.id.vms_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new VmAdapter();
        recycler.setAdapter(adapter);

        view.findViewById(R.id.vm_create_btn).setOnClickListener(v -> promptCreate());
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshHandler.postDelayed(this::tick, 3000);
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshHandler.removeCallbacksAndMessages(null);
    }

    private void tick() {
        refresh();
        refreshHandler.postDelayed(this::tick, 5000);
    }

    private void refresh() {
        List<VmRegistry.VmInfo> vms = reg.list();
        adapter.submit(vms);
        TextView cap = getView() == null ? null : getView().findViewById(R.id.vm_cap_note);
        if (cap != null) {
            cap.setText("Running " + runningCount(vms) + " / " + VmRegistry.MAX_VMS + " (hard cap)");
        }
    }

    private int runningCount(List<VmRegistry.VmInfo> vms) {
        int n = 0;
        for (VmRegistry.VmInfo vm : vms) {
            RootlessEngine e = reg.engine(requireContext(), vm.id);
            if (e != null && e.status() == RootlessEngine.State.READY) n++;
        }
        return n;
    }

    private void promptCreate() {
        if (reg.atCapacity()) {
            toast("VM capacity reached (" + VmRegistry.MAX_VMS + " max)");
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("VM name");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Create VM")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    try {
                        VmRegistry.VmInfo info = reg.create(input.getText().toString().trim());
                        toast("Created " + info.name + " — provision its disk from the dashboard");
                        refresh();
                    } catch (Exception e) {
                        toast(e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showActions(final VmRegistry.VmInfo vm) {
        final RootlessEngine eng = reg.engine(requireContext(), vm.id);
        final boolean selected = vm.id.equals(reg.selected());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(vm.name + " (" + vm.id + ")" + (selected ? "  · selected" : ""))
                .setItems(new String[]{"Start", "Stop", "Open terminal", "Select", "Configure (CPU/RAM)",
                        "Clone", "Snapshot", "Reset", "Delete"}, (d, idx) -> {
                    switch (idx) {
                        case 0:
                            if (!reg.canStart()) {
                                toast("2 VMs already running — stop one first");
                                break;
                            }
                            new Thread(() -> eng.startBlocking(null), "vm-start").start();
                            break;
                        case 1: new Thread(eng::stop, "vm-stop").start(); break;
                        case 2: openTerminal(vm); break;
                        case 3: reg.select(vm.id); refresh(); break;
                        case 4: promptConfigure(vm); break;
                        case 5: promptClone(vm); break;
                        case 6: promptSnapshot(vm); break;
                        case 7: resetVm(vm); break;
                        case 8:
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Delete " + vm.name)
                                    .setMessage("Delete this VM and its disk? This is destructive.")
                                    .setPositiveButton("Delete", (dd, ww) -> {
                                        reg.remove(vm.id, true);
                                        toast(vm.name + " deleted");
                                        refresh();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void openTerminal(VmRegistry.VmInfo vm) {
        reg.select(vm.id);
        if (reg.engine(requireContext(), vm.id).status() == RootlessEngine.State.STOPPED) {
            toast(vm.name + " is stopped — start it first");
            return;
        }
        Intent t = new Intent(requireContext(), com.stryker.terminal.ui.term.NeoTermActivity.class);
        t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        t.putExtra(com.stryker.terminal.ui.term.NeoTermActivity.EXTRA_NEW_SESSION, true);
        requireContext().startActivity(t);
    }

    private void promptConfigure(final VmRegistry.VmInfo vm) {
        final int curCpus = VmSpecs.effectiveCpus(requireContext(), core, vm.index);
        final int curRam = VmSpecs.effectiveRamMb(requireContext(), core, vm.index);

        final android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);

        final android.widget.EditText cpuIn = new android.widget.EditText(requireContext());
        cpuIn.setHint("vCPUs (1-" + VmSpecs.deviceCores() + ")");
        cpuIn.setText(String.valueOf(curCpus));
        cpuIn.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        final android.widget.EditText ramIn = new android.widget.EditText(requireContext());
        ramIn.setHint("RAM MB (" + VmSpecs.MIN_RAM_MB + "-" + VmSpecs.maxRamMb(requireContext()) + ")");
        ramIn.setText(String.valueOf(curRam));
        ramIn.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        box.addView(cpuIn);
        box.addView(ramIn);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Configure " + vm.name + " — CPU/RAM")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int c = Integer.parseInt(cpuIn.getText().toString().trim());
                        int r = Integer.parseInt(ramIn.getText().toString().trim());
                        VmSpecs.setCpus(requireContext(), core, vm.index, c);
                        VmSpecs.setRamMb(requireContext(), core, vm.index, r);
                        toast("Saved: " + VmSpecs.effectiveCpus(requireContext(), core, vm.index)
                                + " vCPU / " + VmSpecs.effectiveRamMb(requireContext(), core, vm.index) + " MB");
                        refresh();
                    } catch (NumberFormatException e) {
                        toast("Enter numbers for CPU and RAM");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptClone(final VmRegistry.VmInfo vm) {
        if (reg.atCapacity()) { toast("VM capacity reached"); return; }
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("New VM name");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clone " + vm.name)
                .setView(input)
                .setPositiveButton("Clone", (d, w) -> new Thread(() -> {
                    try {
                        VmRegistry.VmInfo info = reg.clone(vm.id, input.getText().toString().trim(), 0);
                        requireActivity().runOnUiThread(() -> { toast("Cloned " + vm.name + " → " + info.name); refresh(); });
                    } catch (Exception e) {
                        requireActivity().runOnUiThread(() -> toast(e.getMessage()));
                    }
                }, "vm-clone").start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptSnapshot(final VmRegistry.VmInfo vm) {
        if (reg.atCapacity()) { toast("VM capacity reached"); return; }
        final String snapName = vm.name + "-snap-"
                + new java.text.SimpleDateFormat("ddMM_HHmm", java.util.Locale.ENGLISH).format(new java.util.Date());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Snapshot " + vm.name)
                .setMessage("Create a point-in-time copy named \"" + snapName + "\"?")
                .setPositiveButton("Snapshot", (d, w) -> new Thread(() -> {
                    try {
                        VmRegistry.VmInfo info = reg.clone(vm.id, snapName, 0);
                        requireActivity().runOnUiThread(() -> { toast("Snapshot saved: " + info.name); refresh(); });
                    } catch (Exception e) {
                        requireActivity().runOnUiThread(() -> toast(e.getMessage()));
                    }
                }, "vm-snapshot").start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resetVm(final VmRegistry.VmInfo vm) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reset " + vm.name)
                .setMessage("Delete this VM's disk and re-provision it from scratch? This is destructive.")
                .setPositiveButton("Reset", (d, w) -> {
                    reg.remove(vm.id, true);
                    toast(vm.name + " reset — re-provisioning…");
                    startActivity(new Intent(requireContext(), AppIntroActivity.class)
                            .putExtra(AppIntroActivity.EXTRA_INSTALL_ENGINE, EngineType.ROOTLESS.name()));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private class VmAdapter extends RecyclerView.Adapter<VmAdapter.Holder> {
        private List<VmRegistry.VmInfo> vms = new ArrayList<>();

        void submit(List<VmRegistry.VmInfo> list) {
            vms = new ArrayList<>(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vm, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            VmRegistry.VmInfo vm = vms.get(position);
            h.name.setText(vm.name);
            h.specs.setText(buildSpecs(vm));
            RootlessEngine e = reg.engine(requireContext(), vm.id);
            RootlessEngine.State st = e == null ? RootlessEngine.State.STOPPED : e.status();
            h.status.setText(st.name());
            int color = st == RootlessEngine.State.READY ? R.color.stryker_accent
                    : st == RootlessEngine.State.BOOTING ? R.color.yellow : R.color.grey;
            h.status.setTextColor(ContextCompat.getColor(requireContext(), color));
            h.itemView.setOnClickListener(v -> showActions(vm));
        }

        private String buildSpecs(VmRegistry.VmInfo vm) {
            int cpus = VmSpecs.effectiveCpus(requireContext(), core, vm.index);
            int ram = VmSpecs.effectiveRamMb(requireContext(), core, vm.index);
            long disk = RootlessPaths.rootfs(requireContext(), vm.id).length();
            return cpus + " vCPU · " + ram + " MB · " + VmSpecs.humanBytes(disk) + " disk";
        }

        @Override
        public int getItemCount() {
            return vms.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView specs;
            final TextView status;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.vm_row_name);
                specs = v.findViewById(R.id.vm_row_specs);
                status = v.findViewById(R.id.vm_row_status);
            }
        }
    }
}
