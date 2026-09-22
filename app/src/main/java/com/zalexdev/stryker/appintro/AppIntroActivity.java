package com.zalexdev.stryker.appintro;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textview.MaterialTextView;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.slides.Slide1;
import com.zalexdev.stryker.appintro.slides.Slide2;
import com.zalexdev.stryker.appintro.slides.Slide3;
import com.zalexdev.stryker.appintro.slides.Slide6Final;
import com.zalexdev.stryker.appintro.slides.SlideEngineSelect;
import com.zalexdev.stryker.appintro.slides.SlidePCheck;
import com.zalexdev.stryker.appintro.slides.SlideQemuInstall;
import com.zalexdev.stryker.engine.EngineType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class AppIntroActivity extends FragmentActivity {

    public static final String EXTRA_MIGRATE = "migrate_legacy_chroot";
    public static final String EXTRA_INSTALL_ENGINE = "install_engine";

    public enum Page { CONSENT, ENGINE, PERMS, PCHECK, INSTALL_CHROOT, INSTALL_QEMU, FINAL }

    public boolean isMigration() {
        return getIntent() != null && getIntent().getBooleanExtra(EXTRA_MIGRATE, false);
    }

    private final List<Page> pages = new ArrayList<>(Arrays.asList(
            Page.CONSENT, Page.ENGINE, Page.PERMS, Page.PCHECK, Page.INSTALL_CHROOT, Page.FINAL));

    private ViewPager2 mPager;
    private ScreenPagerAdapter pagerAdapter;
    private LinearProgressIndicator progress;
    private MaterialTextView stepLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_intro);

        mPager = findViewById(R.id.view_pager);
        progress = findViewById(R.id.intro_progress);
        stepLabel = findViewById(R.id.intro_step);

        mPager.setUserInputEnabled(false);
        mPager.setPageTransformer(new SlideFadeTransformer());
        pagerAdapter = new ScreenPagerAdapter(this, pages);
        mPager.setAdapter(pagerAdapter);
        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { bindProgress(position); }
        });

        ImageView logo = findViewById(R.id.logo);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        logo.setImageResource(dark ? R.drawable.ic_white : R.drawable.ic_blue);
        logo.setAlpha(0f);
        logo.setScaleX(0.85f);
        logo.setScaleY(0.85f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(getResources().getInteger(R.integer.motion_long))
                .start();

        bindProgress(0);

        // Re-entry to install a specific engine (e.g. "install rootless" from the drawer).
        String installEngine = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_INSTALL_ENGINE) : null;
        if (installEngine != null) {
            try {
                EngineType type = EngineType.valueOf(installEngine);
                applyEngineFlow(type);
                jumpTo(type == EngineType.ROOTLESS ? Page.INSTALL_QEMU : Page.INSTALL_CHROOT);
            } catch (Throwable ignored) {
            }
        }
    }

    public void applyEngineFlow(EngineType type) {
        applyEngineFlow(Collections.singleton(type));
    }

    public void applyEngineFlow(Set<EngineType> selected) {
        while (pages.size() > 2) pages.remove(pages.size() - 1);
        pages.add(Page.PERMS);
        if (selected.contains(EngineType.CHROOT)) {
            pages.add(Page.PCHECK);
            pages.add(Page.INSTALL_CHROOT);
        }
        if (selected.contains(EngineType.ROOTLESS)) {
            pages.add(Page.INSTALL_QEMU);
        }
        pages.add(Page.FINAL);
        pagerAdapter.notifyDataSetChanged();
        bindProgress(mPager.getCurrentItem());
    }

    public void jumpToLast() {
        mPager.setCurrentItem(pages.size() - 1);
    }

    public void jumpTo(Page target) {
        int idx = pages.indexOf(target);
        if (idx >= 0) mPager.setCurrentItem(idx, false);
    }

    private void bindProgress(int position) {
        int total = pages.size();
        int step = Math.min(Math.max(position + 1, 1), total);
        if (progress != null) {
            progress.setMax(total);
            progress.setProgressCompat(step, true);
        }
        if (stepLabel != null) {
            stepLabel.setText(getString(R.string.intro_step_of, step, total));
        }
    }

    @Override
    public void onBackPressed() {
    }

    private static class ScreenPagerAdapter extends FragmentStateAdapter {
        private final List<Page> pages;

        ScreenPagerAdapter(@NonNull FragmentActivity a, List<Page> pages) {
            super(a);
            this.pages = pages;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (pages.get(position)) {
                case CONSENT: return new Slide1();
                case ENGINE: return new SlideEngineSelect();
                case PERMS: return new Slide2();
                case PCHECK: return new SlidePCheck();
                case INSTALL_CHROOT: return new Slide3();
                case INSTALL_QEMU: return new SlideQemuInstall();
                case FINAL:
                default: return new Slide6Final();
            }
        }

        @Override
        public int getItemCount() { return pages.size(); }

        @Override
        public long getItemId(int position) { return pages.get(position).ordinal(); }

        @Override
        public boolean containsItem(long itemId) {
            for (Page p : pages) if (p.ordinal() == itemId) return true;
            return false;
        }
    }

    private static class SlideFadeTransformer implements ViewPager2.PageTransformer {
        @Override
        public void transformPage(@NonNull View page, float position) {
            float abs = Math.abs(position);
            if (abs >= 1f) {
                page.setAlpha(0f);
                page.setTranslationX(0f);
                return;
            }
            page.setAlpha(1f - abs);
            page.setTranslationX(-position * page.getWidth() * 0.12f);
        }
    }
}
