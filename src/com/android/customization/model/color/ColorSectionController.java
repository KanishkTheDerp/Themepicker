/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.customization.model.color;

import static android.view.View.VISIBLE;

import static com.android.customization.model.color.ColorOptionsProvider.COLOR_SOURCE_HOME;
import static com.android.customization.model.color.ColorOptionsProvider.COLOR_SOURCE_LOCK;
import static com.android.customization.model.color.ColorOptionsProvider.COLOR_SOURCE_PRESET;
import static com.android.customization.widget.OptionSelectorController.CheckmarkStyle.CENTER;

import android.app.Activity;
import android.app.WallpaperColors;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.stats.style.StyleEnums;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.customization.model.CustomizationManager;
import com.android.customization.model.theme.OverlayManagerCompat;
import com.android.customization.module.CustomizationInjector;
import com.android.customization.module.ThemesUserEventLogger;
import com.android.customization.picker.color.ColorSectionView;
import com.android.customization.widget.OptionSelectorController;
import com.android.wallpaper.R;
import com.android.wallpaper.model.CustomizationSectionController;
import com.android.wallpaper.model.WallpaperColorsViewModel;
import com.android.wallpaper.module.InjectorProvider;
import com.android.wallpaper.widget.PageIndicator;
import com.android.wallpaper.widget.SeparatedTabLayout;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Color section view's controller for the logic of color customization.
 */
public class ColorSectionController implements CustomizationSectionController<ColorSectionView> {

    private static final String TAG = "ColorSectionController";
    private static final String KEY_COLOR_TAB_POSITION = "COLOR_TAB_POSITION";
    private static final long MIN_COLOR_APPLY_PERIOD = 500L;

    private static final int WALLPAPER_TAB_INDEX = 0;
    private static final int PRESET_TAB_INDEX = 1;

    private final ThemesUserEventLogger mEventLogger;
    private final ColorCustomizationManager mColorManager;
    private final WallpaperColorsViewModel mWallpaperColorsViewModel;
    private final LifecycleOwner mLifecycleOwner;
    private final ColorSectionAdapter mColorSectionAdapter = new ColorSectionAdapter();
    private final List<ColorOption> mWallpaperColorOptions = new ArrayList<>();
    private final List<ColorOption> mPresetColorOptions = new ArrayList<>();

    private ViewPager2 mColorSectionViewPager;
    private ColorOption mSelectedColor;
    private SeparatedTabLayout mTabLayout;
    @Nullable private WallpaperColors mHomeWallpaperColors;
    @Nullable private WallpaperColors mLockWallpaperColors;
    // Uses a boolean value to indicate whether wallpaper color is ready because WallpaperColors
    // maybe be null when it's ready.
    private boolean mHomeWallpaperColorsReady;
    private boolean mLockWallpaperColorsReady;
    private Optional<Integer> mTabPositionToRestore = Optional.empty();
    private long mLastColorApplyingTime = 0L;
    private ColorSectionView mColorSectionView;

    private static int getNumPages(int optionsPerPage, int totalOptions) {
        return (int) Math.ceil((float) totalOptions / optionsPerPage);
    }

    public ColorSectionController(Activity activity, WallpaperColorsViewModel viewModel,
            LifecycleOwner lifecycleOwner, @Nullable Bundle savedInstanceState) {
        CustomizationInjector injector = (CustomizationInjector) InjectorProvider.getInjector();
        mEventLogger = (ThemesUserEventLogger) injector.getUserEventLogger(activity);
        mColorManager = ColorCustomizationManager.getInstance(activity,
                new OverlayManagerCompat(activity));
        mWallpaperColorsViewModel = viewModel;
        mLifecycleOwner = lifecycleOwner;

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_COLOR_TAB_POSITION)) {
            mTabPositionToRestore = Optional.of(savedInstanceState.getInt(KEY_COLOR_TAB_POSITION));
        }
    }

    @Override
    public boolean isAvailable(@Nullable Context context) {
        return context != null && ColorUtils.isMonetEnabled(context) && mColorManager.isAvailable();
    }

    @Override
    public ColorSectionView createView(Context context) {
        mColorSectionView = (ColorSectionView) LayoutInflater.from(context).inflate(
                R.layout.color_section_view, /* root= */ null);
        mColorSectionViewPager = mColorSectionView.findViewById(R.id.color_section_view_pager);
        mColorSectionViewPager.setAdapter(mColorSectionAdapter);
        mColorSectionViewPager.setUserInputEnabled(false);
        mTabLayout = mColorSectionView.findViewById(R.id.separated_tabs);
        mColorSectionAdapter.setNumColors(context.getResources().getInteger(
                R.integer.options_grid_num_columns));
        // TODO(b/202145216): Use just 2 views when tapping either button on top.
        mTabLayout.setViewPager(mColorSectionViewPager);

        mWallpaperColorsViewModel.getHomeWallpaperColors().observe(mLifecycleOwner,
                homeColors -> {
                    mHomeWallpaperColors = homeColors;
                    mHomeWallpaperColorsReady = true;
                    maybeLoadColors();
                });
        mWallpaperColorsViewModel.getLockWallpaperColors().observe(mLifecycleOwner,
                lockColors -> {
                    mLockWallpaperColors = lockColors;
                    mLockWallpaperColorsReady = true;
                    maybeLoadColors();
                });
        return mColorSectionView;
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        if (mColorSectionViewPager != null) {
            savedInstanceState.putInt(KEY_COLOR_TAB_POSITION,
                    mColorSectionViewPager.getCurrentItem());
        }
    }

    private void maybeLoadColors() {
        if (mHomeWallpaperColorsReady && mLockWallpaperColorsReady) {
            mColorManager.setWallpaperColors(mHomeWallpaperColors, mLockWallpaperColors);
            loadColorOptions(/* reload= */ false);
        }
    }

    private void loadColorOptions(boolean reload) {
        mColorManager.fetchOptions(new CustomizationManager.OptionsFetchedListener<ColorOption>() {
            @Override
            public void onOptionsLoaded(List<ColorOption> options) {
                mWallpaperColorOptions.clear();
                mPresetColorOptions.clear();

                for (ColorOption option : options) {
                    if (option instanceof ColorSeedOption) {
                        mWallpaperColorOptions.add(option);
                    } else if (option instanceof ColorBundle) {
                        mPresetColorOptions.add(option);
                    }
                }
                mSelectedColor = findActiveColorOption(mWallpaperColorOptions,
                        mPresetColorOptions);
                mTabLayout.post(()-> setUpColorViewPager());
            }

            @Override
            public void onError(@Nullable Throwable throwable) {
                if (throwable != null) {
                    Log.e(TAG, "Error loading theme bundles", throwable);
                }
            }
        }, reload);
    }

    private void setUpColorViewPager() {
        mColorSectionAdapter.notifyDataSetChanged();

        if (mTabLayout != null && mTabLayout.getTabCount() == 0) {
            mTabLayout.addTab(mTabLayout.newTab().setText(R.string.wallpaper_color_tab),
                    WALLPAPER_TAB_INDEX);
            mTabLayout.addTab(mTabLayout.newTab().setText(R.string.preset_color_tab),
                    PRESET_TAB_INDEX);
        }

        if (mWallpaperColorOptions.isEmpty()) {
            // Select preset tab and disable wallpaper tab.
            mTabLayout.getTabAt(WALLPAPER_TAB_INDEX).view.setEnabled(false);
            mColorSectionViewPager.setCurrentItem(PRESET_TAB_INDEX, /* smoothScroll= */ false);
            return;
        }

        mColorSectionViewPager.setCurrentItem(
                mTabPositionToRestore.orElseGet(
                        () -> COLOR_SOURCE_PRESET.equals(mColorManager.getCurrentColorSource())
                                ? PRESET_TAB_INDEX
                                : WALLPAPER_TAB_INDEX),
                /* smoothScroll= */ false);

        // Disable "wallpaper colors" and "basic colors" swiping for new color style.
        mColorSectionViewPager.setUserInputEnabled(!ColorProvider.themeStyleEnabled);
    }

    private void setupWallpaperColorPages(ViewPager2 container, int colorsPerPage,
            PageIndicator pageIndicator) {
        container.setAdapter(new ColorPageAdapter(mWallpaperColorOptions, /* pageEnabled= */ true,
                colorsPerPage));
        if (ColorProvider.themeStyleEnabled) {
            // Update page index to show selected items.
            int selectedIndex = mWallpaperColorOptions.indexOf(mSelectedColor);
            if (selectedIndex >= 0 && colorsPerPage != 0) {
                int pageIndex = selectedIndex / colorsPerPage;
                container.setCurrentItem(pageIndex, /* smoothScroll= */ false);
            }
            pageIndicator.setNumPages(getNumPages(colorsPerPage, mWallpaperColorOptions.size()));
            registerOnPageChangeCallback(container, pageIndicator);
        }
    }

    private void setupPresetColorPages(ViewPager2 container, int colorsPerPage,
            PageIndicator pageIndicator) {
        container.setAdapter(new ColorPageAdapter(mPresetColorOptions, /* pageEnabled= */ true,
                colorsPerPage));
        if (ColorProvider.themeStyleEnabled) {
            // Update page index to show selected items.
            int selectedIndex = mPresetColorOptions.indexOf(mSelectedColor);
            if (selectedIndex >= 0 && colorsPerPage != 0) {
                int pageIndex = selectedIndex / colorsPerPage;
                container.setCurrentItem(pageIndex, /* smoothScroll= */ false);
            }
            pageIndicator.setNumPages(getNumPages(colorsPerPage, mPresetColorOptions.size()));
            registerOnPageChangeCallback(container, pageIndicator);
        }
    }

    private void registerOnPageChangeCallback(ViewPager2 container, PageIndicator pageIndicator) {
        container.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                pageIndicator.setLocation(position);
            }

            @Override
            public void onPageScrolled(int position, float positionOffset,
                    int positionOffsetPixels) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels);
                pageIndicator.setLocation(position);
            }
        });
    }

    private void setupColorOptions(RecyclerView container, List<ColorOption> colorOptions,
            boolean pageEnabled, int index, int colorsPerPage) {
        int totalSize = colorOptions.size();
        if (totalSize == 0) {
            return;
        }

        List<ColorOption> subOptions;
        if (pageEnabled && ColorProvider.themeStyleEnabled) {
            subOptions = colorOptions.subList(colorsPerPage * index,
                    Math.min(colorsPerPage * (index + 1), totalSize));
        } else {
            subOptions = colorOptions;
        }

        OptionSelectorController<ColorOption> adaptiveController = new OptionSelectorController<>(
                container, subOptions, /* useGrid= */ true, CENTER);
        adaptiveController.initOptions(mColorManager);
        setUpColorOptionsController(adaptiveController);
    }

    private ColorOption findActiveColorOption(List<ColorOption> wallpaperColorOptions,
            List<ColorOption> presetColorOptions) {
        ColorOption activeColorOption = null;
        for (ColorOption colorOption : Lists.newArrayList(
                Iterables.concat(wallpaperColorOptions, presetColorOptions))) {
            if (colorOption.isActive(mColorManager)) {
                activeColorOption = colorOption;
                break;
            }
        }
        // Use the first one option by default. This should not happen as above should have an
        // active option found.
        if (activeColorOption == null) {
            activeColorOption = wallpaperColorOptions.isEmpty()
                    ? presetColorOptions.get(0)
                    : wallpaperColorOptions.get(0);
        }
        return activeColorOption;
    }

    private void setUpColorOptionsController(
            OptionSelectorController<ColorOption> optionSelectorController) {
        if (mSelectedColor != null && optionSelectorController.containsOption(mSelectedColor)) {
            optionSelectorController.setSelectedOption(mSelectedColor);
        }

        optionSelectorController.addListener(selectedOption -> {
            ColorOption selectedColor = (ColorOption) selectedOption;
            if (mSelectedColor.equals(selectedColor)) {
                return;
            }
            mSelectedColor = (ColorOption) selectedOption;
            // Post with delay for color option to run ripple.
            new Handler().postDelayed(()-> applyColor(mSelectedColor), /* delayMillis= */ 100);
        });
    }

    private void applyColor(ColorOption colorOption) {
        if (SystemClock.elapsedRealtime() - mLastColorApplyingTime < MIN_COLOR_APPLY_PERIOD) {
            return;
        }
        mLastColorApplyingTime = SystemClock.elapsedRealtime();
        mColorManager.apply(colorOption, new CustomizationManager.Callback() {
            @Override
            public void onSuccess() {
                mColorSectionView.announceForAccessibility(
                        mColorSectionView.getContext().getString(R.string.color_changed));
                mEventLogger.logColorApplied(getColorAction(colorOption), colorOption.getIndex());
            }

            @Override
            public void onError(@Nullable Throwable throwable) {
                Log.w(TAG, "Apply theme with error: " + throwable);
            }
        });
    }

    private int getColorAction(ColorOption colorOption) {
        int action = StyleEnums.DEFAULT_ACTION;
        boolean isForBoth = mLockWallpaperColors == null || mLockWallpaperColors.equals(
                mHomeWallpaperColors);

        if (TextUtils.equals(colorOption.getSource(), COLOR_SOURCE_PRESET)) {
            action = StyleEnums.COLOR_PRESET_APPLIED;
        } else if (isForBoth) {
            action = StyleEnums.COLOR_WALLPAPER_HOME_LOCK_APPLIED;
        } else {
            switch (colorOption.getSource()) {
                case COLOR_SOURCE_HOME:
                    action = StyleEnums.COLOR_WALLPAPER_HOME_APPLIED;
                    break;
                case COLOR_SOURCE_LOCK:
                    action = StyleEnums.COLOR_WALLPAPER_LOCK_APPLIED;
                    break;
            }
        }
        return action;
    }

    private class ColorSectionAdapter extends
            RecyclerView.Adapter<ColorSectionAdapter.ColorPageViewHolder> {

        private final int mItemCounts = new int[]{WALLPAPER_TAB_INDEX, PRESET_TAB_INDEX}.length;
        private int mNumColors;

        @Override
        public int getItemCount() {
            return mItemCounts;
        }

        @Override
        public void onBindViewHolder(ColorPageViewHolder viewHolder, int position) {
            switch (position) {
                case WALLPAPER_TAB_INDEX:
                    setupWallpaperColorPages(viewHolder.mContainer, mNumColors,
                            viewHolder.mPageIndicator);
                    break;
                case PRESET_TAB_INDEX:
                    setupPresetColorPages(viewHolder.mContainer, mNumColors,
                            viewHolder.mPageIndicator);
                    break;
                default:
                    break;
            }
        }

        @Override
        public ColorPageViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            return new ColorPageViewHolder(LayoutInflater.from(viewGroup.getContext()).inflate(
                    viewType, viewGroup, false));
        }

        @Override
        public int getItemViewType(int position) {
            return R.layout.color_pages_view;
        }

        public void setNumColors(int numColors) {
            mNumColors = numColors;
        }

        private class ColorPageViewHolder extends RecyclerView.ViewHolder {
            private ViewPager2 mContainer;
            private PageIndicator mPageIndicator;

            ColorPageViewHolder(View itemView) {
                super(itemView);
                mContainer = itemView.findViewById(R.id.color_page_container);
                mPageIndicator = itemView.findViewById(R.id.color_page_indicator);
                if (ColorProvider.themeStyleEnabled) {
                    mPageIndicator.setVisibility(VISIBLE);
                }
            }
        }
    }

    private class ColorPageAdapter extends
            RecyclerView.Adapter<ColorPageAdapter.ColorOptionViewHolder> {

        private final boolean mPageEnabled;
        private final List<ColorOption> mColorOptions;
        private final int mColorsPerPage;

        private ColorPageAdapter(List<ColorOption> colorOptions, boolean pageEnabled,
                int colorsPerPage) {
            mPageEnabled = pageEnabled;
            mColorOptions = colorOptions;
            mColorsPerPage = colorsPerPage;
        }

        @Override
        public int getItemCount() {
            if (!mPageEnabled || !ColorProvider.themeStyleEnabled) {
                return 1;
            }
            // Color page size.
            return getNumPages(mColorsPerPage, mColorOptions.size());
        }

        @Override
        public void onBindViewHolder(ColorOptionViewHolder viewHolder, int position) {
            setupColorOptions(viewHolder.mContainer, mColorOptions, mPageEnabled, position,
                    mColorsPerPage);
        }

        @Override
        public ColorOptionViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            return new ColorOptionViewHolder(
                    LayoutInflater.from(viewGroup.getContext()).inflate(viewType, viewGroup,
                            false));
        }

        @Override
        public int getItemViewType(int position) {
            return R.layout.color_options_view;
        }

        private class ColorOptionViewHolder extends RecyclerView.ViewHolder {
            private RecyclerView mContainer;

            ColorOptionViewHolder(View itemView) {
                super(itemView);
                mContainer = itemView.findViewById(R.id.color_option_container);
            }
        }
    }
}