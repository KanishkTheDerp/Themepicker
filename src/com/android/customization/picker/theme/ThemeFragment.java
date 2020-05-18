/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.customization.picker.theme;

import static android.app.Activity.RESULT_OK;

import static com.android.customization.picker.ViewOnlyFullPreviewActivity.SECTION_STYLE;
import static com.android.customization.picker.theme.ThemeFullPreviewFragment.EXTRA_THEME_OPTION;
import static com.android.customization.picker.theme.ThemeFullPreviewFragment.EXTRA_THEME_OPTION_TITLE;
import static com.android.customization.picker.theme.ThemeFullPreviewFragment.EXTRA_WALLPAPER_INFO;
import static com.android.wallpaper.widget.BottomActionBar.BottomAction.APPLY;

import android.app.Activity;
import android.app.WallpaperColors;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.ContentLoadingProgressBar;
import androidx.recyclerview.widget.RecyclerView;

import com.android.customization.model.CustomizationManager.Callback;
import com.android.customization.model.CustomizationManager.OptionsFetchedListener;
import com.android.customization.model.theme.ThemeBundle;
import com.android.customization.model.theme.ThemeBundle.PreviewInfo;
import com.android.customization.model.theme.ThemeManager;
import com.android.customization.model.theme.custom.CustomTheme;
import com.android.customization.module.ThemesUserEventLogger;
import com.android.customization.picker.BasePreviewAdapter;
import com.android.customization.picker.TimeTicker;
import com.android.customization.picker.ViewOnlyFullPreviewActivity;
import com.android.customization.picker.WallpaperPreviewer;
import com.android.customization.picker.theme.ThemePreviewPage.ThemeCoverPage;
import com.android.customization.picker.theme.ThemePreviewPage.TimeContainer;
import com.android.customization.widget.OptionSelectorController;
import com.android.wallpaper.R;
import com.android.wallpaper.asset.Asset;
import com.android.wallpaper.asset.Asset.CenterCropBitmapTask;
import com.android.wallpaper.asset.BitmapCachingAsset;
import com.android.wallpaper.model.WallpaperInfo;
import com.android.wallpaper.module.CurrentWallpaperInfoFactory;
import com.android.wallpaper.module.InjectorProvider;
import com.android.wallpaper.picker.AppbarFragment;
import com.android.wallpaper.widget.BottomActionBar;
import com.android.wallpaper.widget.PreviewPager;

import java.util.List;

/**
 * Fragment that contains the main UI for selecting and applying a ThemeBundle.
 */
public class ThemeFragment extends AppbarFragment {

    private static final String TAG = "ThemeFragment";
    private static final String KEY_SELECTED_THEME = "ThemeFragment.SelectedThemeBundle";
    private static final int FULL_PREVIEW_REQUEST_CODE = 1000;

    private static final boolean USE_NEW_PREVIEW = false;

    /**
     * Interface to be implemented by an Activity hosting a {@link ThemeFragment}
     */
    public interface ThemeFragmentHost {
        ThemeManager getThemeManager();
    }
    public static ThemeFragment newInstance(CharSequence title) {
        ThemeFragment fragment = new ThemeFragment();
        fragment.setArguments(AppbarFragment.createArguments(title));
        return fragment;
    }

    private RecyclerView mOptionsContainer;
    private OptionSelectorController<ThemeBundle> mOptionsController;
    private ThemeManager mThemeManager;
    private ThemesUserEventLogger mEventLogger;
    private ThemeBundle mSelectedTheme;
    private ThemePreviewAdapter mAdapter;
    private PreviewPager mPreviewPager;
    private ContentLoadingProgressBar mLoading;
    private View mContent;
    private View mError;
    private boolean mUseMyWallpaper = true;
    private WallpaperInfo mCurrentHomeWallpaper;
    private Asset mCurrentWallpaperThumbAsset;
    private CurrentWallpaperInfoFactory mCurrentWallpaperFactory;
    private TimeTicker mTicker;
    private BottomActionBar mBottomActionBar;
    private WallpaperPreviewer mWallpaperPreviewer;
    private ThemeOptionPreviewer mThemeOptionPreviewer;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mThemeManager = ((ThemeFragmentHost) context).getThemeManager();
        mEventLogger = (ThemesUserEventLogger)
                InjectorProvider.getInjector().getUserEventLogger(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_theme_picker, container, /* attachToRoot */ false);
        setUpToolbar(view);

        mContent = view.findViewById(R.id.content_section);
        mLoading = view.findViewById(R.id.loading_indicator);
        mError = view.findViewById(R.id.error_section);
        mCurrentWallpaperFactory = InjectorProvider.getInjector()
                .getCurrentWallpaperFactory(getActivity().getApplicationContext());
        mPreviewPager = view.findViewById(R.id.theme_preview_pager);
        mOptionsContainer = view.findViewById(R.id.options_container);

        if (USE_NEW_PREVIEW) {
            mPreviewPager.setVisibility(View.GONE);
            view.findViewById(R.id.preview_card_container).setVisibility(View.VISIBLE);
            // Set Wallpaper background.
            mWallpaperPreviewer = new WallpaperPreviewer(
                    getLifecycle(),
                    getActivity(),
                    view.findViewById(R.id.wallpaper_preview_image),
                    view.findViewById(R.id.wallpaper_preview_surface));
            mCurrentWallpaperFactory.createCurrentWallpaperInfos(
                    (homeWallpaper, lockWallpaper, presentationMode) -> {
                        mCurrentHomeWallpaper = homeWallpaper;
                        mWallpaperPreviewer.setWallpaper(mCurrentHomeWallpaper);
                    }, false);
            view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    mWallpaperPreviewer.updatePreviewCardRadius();
                    view.removeOnLayoutChangeListener(this);
                }
            });

            ViewGroup previewContainer = view.findViewById(R.id.theme_preview_container);
            previewContainer.setOnClickListener(v -> showFullPreview());
            mThemeOptionPreviewer = new ThemeOptionPreviewer(
                    getLifecycle(),
                    getContext(),
                    previewContainer);
        }
        return view;
    }

    @Override
    protected void onBottomActionBarReady(BottomActionBar bottomActionBar) {
        mBottomActionBar = bottomActionBar;
        mBottomActionBar.showActionsOnly(APPLY);
        mBottomActionBar.setActionClickListener(APPLY, v -> {
            mBottomActionBar.disableActions();
            applyTheme();
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Setup options here when all views are ready(including BottomActionBar), since we need to
        // update views after options are loaded.
        setUpOptions(savedInstanceState);
    }

    private void applyTheme() {
        mThemeManager.apply(mSelectedTheme, new Callback() {
            @Override
            public void onSuccess() {
                // Since we disabled it when clicked apply button.
                mBottomActionBar.enableActions();
                mBottomActionBar.hide();
                Toast.makeText(getContext(), R.string.applied_theme_msg,
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(@Nullable Throwable throwable) {
                Log.w(TAG, "Error applying theme", throwable);
                // Since we disabled it when clicked apply button.
                mBottomActionBar.enableActions();
                mBottomActionBar.hide();
                Toast.makeText(getContext(), R.string.apply_theme_error_msg,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!USE_NEW_PREVIEW) {
            mTicker = TimeTicker.registerNewReceiver(getContext(), this::updateTime);
            reloadWallpaper();
            updateTime();
        }
    }

    private void updateTime() {
        if (mAdapter != null) {
            mAdapter.updateTime();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getContext() != null && !USE_NEW_PREVIEW) {
            getContext().unregisterReceiver(mTicker);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mSelectedTheme != null && !mSelectedTheme.isActive(mThemeManager)) {
            outState.putString(KEY_SELECTED_THEME, mSelectedTheme.getSerializedPackages());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CustomThemeActivity.REQUEST_CODE_CUSTOM_THEME) {
            if (resultCode == CustomThemeActivity.RESULT_THEME_DELETED) {
                mSelectedTheme = null;
                reloadOptions();
            } else if (resultCode == CustomThemeActivity.RESULT_THEME_APPLIED) {
                getActivity().finish();
            } else {
                if (mSelectedTheme != null) {
                    mOptionsController.setSelectedOption(mSelectedTheme);
                    // Set selected option above will show BottomActionBar,
                    // hide BottomActionBar for the mis-trigger.
                    mBottomActionBar.hide();
                } else {
                    reloadOptions();
                }
            }
        } else if (requestCode == FULL_PREVIEW_REQUEST_CODE && resultCode == RESULT_OK) {
            applyTheme();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void reloadWallpaper() {
        mCurrentWallpaperFactory.createCurrentWallpaperInfos(
                (homeWallpaper, lockWallpaper, presentationMode) -> {
                    mCurrentHomeWallpaper = homeWallpaper;
                    mCurrentWallpaperThumbAsset = new BitmapCachingAsset(getContext(),
                            mCurrentHomeWallpaper.getThumbAsset(getContext()));
                    if (mSelectedTheme != null && mAdapter != null) {
                        mAdapter.setWallpaperAsset(mCurrentWallpaperThumbAsset);
                        mAdapter.rebindWallpaperIfAvailable();
                    }
        }, false);
    }

    private void createAdapter(List<ThemeBundle> options) {
        mAdapter = new ThemePreviewAdapter(getActivity(), mSelectedTheme,
                mCurrentWallpaperThumbAsset,
                mSelectedTheme instanceof CustomTheme ? this::onEditClicked : null);
        mPreviewPager.setAdapter(mAdapter);
    }

    private void onEditClicked(View view) {
        if (mSelectedTheme instanceof CustomTheme) {
            navigateToCustomTheme((CustomTheme) mSelectedTheme);
        }
    }

    private void hideError() {
        mContent.setVisibility(View.VISIBLE);
        mError.setVisibility(View.GONE);
    }

    private void showError() {
        mLoading.hide();
        mContent.setVisibility(View.GONE);
        mError.setVisibility(View.VISIBLE);
    }

    private void setUpOptions(@Nullable Bundle savedInstanceState) {
        hideError();
        mLoading.show();
        mThemeManager.fetchOptions(new OptionsFetchedListener<ThemeBundle>() {
            @Override
            public void onOptionsLoaded(List<ThemeBundle> options) {
                mOptionsController = new OptionSelectorController<>(mOptionsContainer, options);
                mOptionsController.addListener(selected -> {
                    mLoading.hide();
                    if (selected instanceof CustomTheme && !((CustomTheme) selected).isDefined()) {
                        navigateToCustomTheme((CustomTheme) selected);
                    } else {
                        mSelectedTheme = (ThemeBundle) selected;
                        if (mUseMyWallpaper || mSelectedTheme instanceof CustomTheme) {
                            mSelectedTheme.setOverrideThemeWallpaper(mCurrentHomeWallpaper);
                        } else {
                            mSelectedTheme.setOverrideThemeWallpaper(null);
                        }
                        mEventLogger.logThemeSelected(mSelectedTheme,
                                selected instanceof CustomTheme);
                        if (USE_NEW_PREVIEW) {
                            mThemeOptionPreviewer.setThemeBundle(mSelectedTheme);
                        } else {
                            createAdapter(options);
                        }
                        mBottomActionBar.show();
                    }
                });
                mOptionsController.initOptions(mThemeManager);
                String previouslySelected = savedInstanceState != null
                        ? savedInstanceState.getString(KEY_SELECTED_THEME) : null;
                for (ThemeBundle theme : options) {
                    if (previouslySelected != null
                            && previouslySelected.equals(theme.getSerializedPackages())) {
                        mSelectedTheme = theme;
                    } else if (theme.isActive(mThemeManager)) {
                        mSelectedTheme = theme;
                        break;
                    }
                }
                if (mSelectedTheme == null) {
                    // Select the default theme if there is no matching custom enabled theme
                    mSelectedTheme = findDefaultThemeBundle(options);
                } else {
                    // Only show show checkmark if we found a matching theme
                    mOptionsController.setAppliedOption(mSelectedTheme);
                }
                mOptionsController.setSelectedOption(mSelectedTheme);
                // Set selected option above will show BottomActionBar when entering the tab. But
                // it should not show when entering the tab.
                mBottomActionBar.hide();
            }
            @Override
            public void onError(@Nullable Throwable throwable) {
                if (throwable != null) {
                    Log.e(TAG, "Error loading theme bundles", throwable);
                }
                showError();
            }
        }, false);
    }

    private void reloadOptions() {
        mThemeManager.fetchOptions(options -> {
            mOptionsController.resetOptions(options);
            for (ThemeBundle theme : options) {
                if (theme.isActive(mThemeManager)) {
                    mSelectedTheme = theme;
                    break;
                }
            }
            if (mSelectedTheme == null) {
                // Select the default theme if there is no matching custom enabled theme
                mSelectedTheme = findDefaultThemeBundle(options);
            } else {
                // Only show show checkmark if we found a matching theme
                mOptionsController.setAppliedOption(mSelectedTheme);
            }
            mOptionsController.setSelectedOption(mSelectedTheme);
            // Set selected option above will show BottomActionBar,
            // hide BottomActionBar for the mis-trigger.
            mBottomActionBar.hide();
        }, true);
    }

    private ThemeBundle findDefaultThemeBundle(List<ThemeBundle> options) {
        String defaultThemeTitle =
                getActivity().getResources().getString(R.string.default_theme_title);
        for (ThemeBundle bundle : options) {
            if (bundle.getTitle().equals(defaultThemeTitle)) {
                return bundle;
            }
        }
        return null;
    }

    private void navigateToCustomTheme(CustomTheme themeToEdit) {
        Intent intent = new Intent(getActivity(), CustomThemeActivity.class);
        intent.putExtra(CustomThemeActivity.EXTRA_THEME_TITLE, themeToEdit.getTitle());
        intent.putExtra(CustomThemeActivity.EXTRA_THEME_ID, themeToEdit.getId());
        intent.putExtra(CustomThemeActivity.EXTRA_THEME_PACKAGES,
                themeToEdit.getSerializedPackages());
        startActivityForResult(intent, CustomThemeActivity.REQUEST_CODE_CUSTOM_THEME);
    }

    private void showFullPreview() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_WALLPAPER_INFO, mCurrentHomeWallpaper);
        bundle.putString(EXTRA_THEME_OPTION, mSelectedTheme.getSerializedPackages());
        bundle.putString(EXTRA_THEME_OPTION_TITLE, mSelectedTheme.getTitle());
        Intent intent = ViewOnlyFullPreviewActivity.newIntent(getContext(), SECTION_STYLE, bundle);
        startActivityForResult(intent, FULL_PREVIEW_REQUEST_CODE);
    }

    /**
     * Adapter class for mPreviewPager.
     * This is a ViewPager as it allows for a nice pagination effect (ie, pages snap on swipe,
     * we don't want to just scroll)
     */
    private static class ThemePreviewAdapter extends BasePreviewAdapter<ThemePreviewPage> {

        private int[] mIconIds = {
                R.id.preview_icon_0, R.id.preview_icon_1, R.id.preview_icon_2, R.id.preview_icon_3,
                R.id.preview_icon_4, R.id.preview_icon_5
        };
        private int[] mColorButtonIds = {
            R.id.preview_check_selected, R.id.preview_radio_selected, R.id.preview_toggle_selected
        };
        private int[] mColorTileIds = {
            R.id.preview_color_qs_0_bg, R.id.preview_color_qs_1_bg, R.id.preview_color_qs_2_bg
        };
        private int[][] mColorTileIconIds = {
                new int[]{ R.id.preview_color_qs_0_icon, 0},
                new int[]{ R.id.preview_color_qs_1_icon, 1},
                new int[] { R.id.preview_color_qs_2_icon, 3}
        };

        private int[] mShapeIconIds = {
                R.id.shape_preview_icon_0, R.id.shape_preview_icon_1, R.id.shape_preview_icon_2,
                R.id.shape_preview_icon_3, R.id.shape_preview_icon_4, R.id.shape_preview_icon_5
        };
        private Asset mWallpaperAsset;

        ThemePreviewAdapter(Activity activity, ThemeBundle theme, @Nullable Asset wallpaperAsset,
                @Nullable OnClickListener editClickListener) {
            super(activity, R.layout.theme_preview_card);
            mWallpaperAsset = wallpaperAsset;
            final Resources res = activity.getResources();
            final PreviewInfo previewInfo = theme.getPreviewInfo();

            Drawable coverScrim = theme instanceof CustomTheme
                    ? res.getDrawable(R.drawable.theme_cover_scrim, activity.getTheme())
                    : null;

            WallpaperPreviewLayoutListener wallpaperListener = new WallpaperPreviewLayoutListener(
                    () -> mWallpaperAsset, previewInfo, coverScrim, true);

            addPage(new ThemeCoverPage(activity, theme.getTitle(),
                    previewInfo.resolveAccentColor(res), previewInfo.icons,
                    previewInfo.headlineFontFamily, previewInfo.bottomSheeetCornerRadius,
                    previewInfo.shapeDrawable, previewInfo.shapeAppIcons, editClickListener,
                    mColorButtonIds, mColorTileIds, mColorTileIconIds, mShapeIconIds,
                    wallpaperListener));
            addPage(new ThemePreviewPage(activity, R.string.preview_name_font, R.drawable.ic_font,
                    R.layout.preview_card_font_content,
                    previewInfo.resolveAccentColor(res)) {
                @Override
                protected void bindBody(boolean forceRebind) {
                    TextView title = card.findViewById(R.id.font_card_title);
                    title.setTypeface(previewInfo.headlineFontFamily);
                    TextView body = card.findViewById(R.id.font_card_body);
                    body.setTypeface(previewInfo.bodyFontFamily);
                    card.findViewById(R.id.font_card_divider).setBackgroundColor(accentColor);
                }
            });
            if (previewInfo.icons.size() >= mIconIds.length) {
                addPage(new ThemePreviewPage(activity, R.string.preview_name_icon,
                        R.drawable.ic_wifi_24px, R.layout.preview_card_icon_content,
                        previewInfo.resolveAccentColor(res)) {
                    @Override
                    protected void bindBody(boolean forceRebind) {
                        for (int i = 0; i < mIconIds.length && i < previewInfo.icons.size(); i++) {
                            ((ImageView) card.findViewById(mIconIds[i]))
                                    .setImageDrawable(previewInfo.icons.get(i)
                                            .getConstantState().newDrawable().mutate());
                        }
                    }
                });
            }
            if (previewInfo.colorAccentDark != -1 && previewInfo.colorAccentLight != -1) {
                addPage(new ThemePreviewPage(activity, R.string.preview_name_color,
                        R.drawable.ic_colorize_24px, R.layout.preview_card_color_content,
                        previewInfo.resolveAccentColor(res)) {
                    @Override
                    protected void bindBody(boolean forceRebind) {
                        int controlGreyColor = res.getColor(R.color.control_grey);
                        ColorStateList tintList = new ColorStateList(
                                new int[][]{
                                    new int[]{android.R.attr.state_selected},
                                    new int[]{android.R.attr.state_checked},
                                    new int[]{-android.R.attr.state_enabled},
                                },
                                new int[] {
                                    accentColor,
                                    accentColor,
                                    controlGreyColor
                                }
                            );

                        for (int i = 0; i < mColorButtonIds.length; i++) {
                            CompoundButton button = card.findViewById(mColorButtonIds[i]);
                            button.setButtonTintList(tintList);
                        }

                        Switch enabledSwitch = card.findViewById(R.id.preview_toggle_selected);
                        enabledSwitch.setThumbTintList(tintList);
                        enabledSwitch.setTrackTintList(tintList);

                        ColorStateList seekbarTintList = ColorStateList.valueOf(accentColor);
                        SeekBar seekbar = card.findViewById(R.id.preview_seekbar);
                        seekbar.setThumbTintList(seekbarTintList);
                        seekbar.setProgressTintList(seekbarTintList);
                        seekbar.setProgressBackgroundTintList(seekbarTintList);
                        // Disable seekbar
                        seekbar.setOnTouchListener((view, motionEvent) -> true);

                        int iconFgColor = res.getColor(R.color.tile_enabled_icon_color, null);
                        for (int i = 0; i < mColorTileIds.length && i < previewInfo.icons.size();
                                i++) {
                            Drawable icon = previewInfo.icons.get(mColorTileIconIds[i][1])
                                    .getConstantState().newDrawable().mutate();
                            icon.setTint(iconFgColor);
                            Drawable bgShape =
                                    previewInfo.shapeDrawable.getConstantState().newDrawable();
                            bgShape.setTint(accentColor);

                            ImageView bg = card.findViewById(mColorTileIds[i]);
                            bg.setImageDrawable(bgShape);
                            ImageView fg = card.findViewById(mColorTileIconIds[i][0]);
                            fg.setImageDrawable(icon);
                        }
                    }
                });
            }
            if (!previewInfo.shapeAppIcons.isEmpty()) {
                addPage(new ThemePreviewPage(activity, R.string.preview_name_shape,
                        R.drawable.ic_shapes_24px, R.layout.preview_card_shape_content,
                        previewInfo.resolveAccentColor(res)) {
                    @Override
                    protected void bindBody(boolean forceRebind) {
                        for (int i = 0; i < mShapeIconIds.length
                                && i < previewInfo.shapeAppIcons.size(); i++) {
                            ImageView iconView = card.findViewById(mShapeIconIds[i]);
                            iconView.setBackground(
                                    previewInfo.shapeAppIcons.get(i));
                        }
                    }
                });
            }
        }

        public void rebindWallpaperIfAvailable() {
            for (ThemePreviewPage page : mPages) {
                if (page.containsWallpaper()) {
                    page.bindBody(true);
                }
            }
        }

        public void updateTime() {
            for (ThemePreviewPage page : mPages) {
                if (page instanceof TimeContainer) {
                    ((TimeContainer)page).updateTime();
                }
            }
        }

        public void setWallpaperAsset(Asset wallpaperAsset) {
            mWallpaperAsset = wallpaperAsset;
        }

        private static class WallpaperPreviewLayoutListener implements OnLayoutChangeListener {
            interface WallpaperPreviewAssetProvider {
                Asset getAsset();
            }
            private final WallpaperPreviewAssetProvider mWallpaperPreviewAssetProvider;
            private final PreviewInfo mPreviewInfo;
            private final Drawable mScrim;
            private final boolean mIsTranslucent;

            WallpaperPreviewLayoutListener(
                    WallpaperPreviewAssetProvider wallpaperPreviewAssetProvider,
                    PreviewInfo previewInfo, Drawable scrim, boolean translucent) {
                mWallpaperPreviewAssetProvider = wallpaperPreviewAssetProvider;
                mPreviewInfo = previewInfo;
                mScrim = scrim;
                mIsTranslucent = translucent;
            }

            @Override
            public void onLayoutChange(View view, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int targetWidth = right - left;
                int targetHeight = bottom - top;
                if (targetWidth > 0 && targetHeight > 0) {
                    Asset wallpaperPreviewAsset = mWallpaperPreviewAssetProvider.getAsset();
                    if (wallpaperPreviewAsset != null) {
                        wallpaperPreviewAsset.decodeBitmap(
                                targetWidth, targetHeight,
                                bitmap -> new CenterCropBitmapTask(bitmap, view,
                                        croppedBitmap -> setWallpaperBitmap(view, croppedBitmap))
                                .execute());
                    }
                    view.removeOnLayoutChangeListener(this);
                }
            }

            private void setWallpaperBitmap(View view, Bitmap bitmap) {
                Resources res = view.getContext().getResources();
                Drawable background = new BitmapDrawable(res, bitmap);
                if (mIsTranslucent) {
                    background.setAlpha(ThemeCoverPage.COVER_PAGE_WALLPAPER_ALPHA);
                }
                if (mScrim != null) {
                    background = new LayerDrawable(new Drawable[]{background, mScrim});
                }
                view.findViewById(R.id.theme_preview_card_background).setBackground(background);
                if (mScrim == null && !mIsTranslucent) {
                    boolean shouldRecycle = false;
                    if (bitmap.getConfig() == Config.HARDWARE) {
                        bitmap = bitmap.copy(Config.ARGB_8888, false);
                        shouldRecycle = true;
                    }
                    int colorsHint = WallpaperColors.fromBitmap(bitmap).getColorHints();
                    if (shouldRecycle) {
                        bitmap.recycle();
                    }
                    TextView header = view.findViewById(R.id.theme_preview_card_header);
                    if ((colorsHint & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0) {
                        int colorLight = res.getColor(R.color.text_color_light, null);
                        header.setTextColor(colorLight);
                        header.setCompoundDrawableTintList(ColorStateList.valueOf(colorLight));
                    } else {
                        header.setTextColor(res.getColor(R.color.text_color_dark, null));
                        header.setCompoundDrawableTintList(ColorStateList.valueOf(
                                mPreviewInfo.colorAccentLight));
                    }
                }
            }
        }
    }
}
