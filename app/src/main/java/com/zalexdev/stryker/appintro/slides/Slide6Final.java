package com.zalexdev.stryker.appintro.slides;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.utils.Core;

public class Slide6Final extends Fragment {

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide5, container, false);
        Core core = new Core(getContext());

        TextView successTitle = view.findViewById(R.id.success_title);
        TextView successSub = view.findViewById(R.id.success_subtitle);
        TextView shellText = view.findViewById(R.id.cap_shell_text);
        boolean chroot = EngineType.chrootInstalled(core);
        boolean vm = core.vmInstalled();
        if (successTitle != null) successTitle.setText("Setup complete");
        if (chroot && vm) {
            if (successSub != null) successSub.setText("Chroot and rootless VM are ready — switch engines from the dashboard");
            if (shellText != null) shellText.setText("Pick an engine on the dashboard, then open a shell");
        } else if (vm) {
            if (successSub != null) successSub.setText("Rootless VM ready — USB adapter auto-attaches for WiFi");
            if (shellText != null) shellText.setText("Drop into the VM shell from the dashboard");
        } else {
            if (successSub != null) successSub.setText("Debian toolset live at " + Core.CHROOT_ROOT);
            if (shellText != null) shellText.setText("Open a chrooted shell from the dashboard");
        }

        MaterialButton button = view.findViewById(R.id.login);
        button.setOnClickListener(view1 -> {
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) return;
            Intent intent = new Intent(activity, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            activity.finish();
        });
        return view;
    }
}
