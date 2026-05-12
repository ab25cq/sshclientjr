package com.sshclientjr;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

final class KeyboardInsetHelper {
    private KeyboardInsetHelper() {
    }

    static void keepAboveKeyboard(Activity activity, View bottomBar) {
        keepAboveKeyboard(activity, bottomBar, 0);
    }

    static void keepAboveKeyboard(Activity activity, View bottomBar, View contentAboveBar, int fallbackKeyboardHeightDp) {
        if (contentAboveBar != null) {
            contentAboveBar.setTag(R.id.keyboard_inset_base_padding_left, contentAboveBar.getPaddingLeft());
            contentAboveBar.setTag(R.id.keyboard_inset_base_padding_top, contentAboveBar.getPaddingTop());
            contentAboveBar.setTag(R.id.keyboard_inset_base_padding_right, contentAboveBar.getPaddingRight());
            contentAboveBar.setTag(R.id.keyboard_inset_base_padding_bottom, contentAboveBar.getPaddingBottom());
        }
        keepAboveKeyboardInternal(activity, bottomBar, contentAboveBar, fallbackKeyboardHeightDp);
    }

    static void keepAboveKeyboard(Activity activity, View bottomBar, int fallbackKeyboardHeightDp) {
        keepAboveKeyboardInternal(activity, bottomBar, null, fallbackKeyboardHeightDp);
    }

    static void keepBelowStatusBar(Activity activity, View topBar) {
        if (topBar == null) {
            return;
        }
        topBar.setTag(R.id.keyboard_inset_base_padding_left, topBar.getPaddingLeft());
        topBar.setTag(R.id.keyboard_inset_base_padding_top, topBar.getPaddingTop());
        topBar.setTag(R.id.keyboard_inset_base_padding_right, topBar.getPaddingRight());
        topBar.setTag(R.id.keyboard_inset_base_padding_bottom, topBar.getPaddingBottom());
        final int fallbackStatusBarHeight = getStatusBarHeight(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            topBar.setOnApplyWindowInsetsListener((view, insets) -> {
                int statusBarHeight = insets.getInsets(WindowInsets.Type.statusBars()).top;
                applyTopBarPadding(topBar, Math.max(statusBarHeight, fallbackStatusBarHeight));
                return insets;
            });
            topBar.requestApplyInsets();
        }
        topBar.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            applyTopBarPadding(topBar, fallbackStatusBarHeight);
        });
    }

    static void keepInsideSystemBars(Activity activity, View rootView) {
        if (rootView == null) {
            return;
        }
        rootView.setTag(R.id.keyboard_inset_base_padding_left, rootView.getPaddingLeft());
        rootView.setTag(R.id.keyboard_inset_base_padding_top, rootView.getPaddingTop());
        rootView.setTag(R.id.keyboard_inset_base_padding_right, rootView.getPaddingRight());
        rootView.setTag(R.id.keyboard_inset_base_padding_bottom, rootView.getPaddingBottom());
        final int fallbackStatusBarHeight = getStatusBarHeight(activity);
        final int fallbackNavigationBarHeight = getNavigationBarHeight(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootView.setOnApplyWindowInsetsListener((view, insets) -> {
                int statusBarHeight = insets.getInsets(WindowInsets.Type.statusBars()).top;
                int navigationBarHeight = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                applySystemBarPadding(
                        rootView,
                        Math.max(statusBarHeight, fallbackStatusBarHeight),
                        Math.max(navigationBarHeight, fallbackNavigationBarHeight)
                );
                return insets;
            });
            rootView.requestApplyInsets();
        }
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            applySystemBarPadding(rootView, fallbackStatusBarHeight, fallbackNavigationBarHeight);
        });
    }

    private static void keepAboveKeyboardInternal(Activity activity, View bottomBar, View contentAboveBar, int fallbackKeyboardHeightDp) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || bottomBar == null) {
            return;
        }
        View root = content.getChildAt(0);
        int keyboardThreshold = dp(activity, 96);
        int fallbackKeyboardHeight = fallbackKeyboardHeightDp <= 0 ? 0 : dp(activity, fallbackKeyboardHeightDp);
        saveBaseMargins(bottomBar);
        final int[] imeBottomInset = {0};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                imeBottomInset[0] = insets.getInsets(WindowInsets.Type.ime()).bottom;
                applyKeyboardOffset(root, bottomBar, contentAboveBar, keyboardThreshold, imeBottomInset[0], fallbackKeyboardHeight);
                return insets;
            });
            root.requestApplyInsets();
        }
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            applyKeyboardOffset(root, bottomBar, contentAboveBar, keyboardThreshold, imeBottomInset[0], fallbackKeyboardHeight);
        });
    }

    static void setManualKeyboardVisible(Activity activity, View bottomBar, View contentAboveBar, boolean visible, int fallbackKeyboardHeightDp) {
        if (contentAboveBar != null && contentAboveBar.getTag(R.id.keyboard_inset_base_padding_bottom) == null) {
            contentAboveBar.setTag(R.id.keyboard_inset_base_padding_left, contentAboveBar.getPaddingLeft());
            contentAboveBar.setTag(R.id.keyboard_inset_base_padding_top, contentAboveBar.getPaddingTop());
            contentAboveBar.setTag(R.id.keyboard_inset_base_padding_right, contentAboveBar.getPaddingRight());
            contentAboveBar.setTag(R.id.keyboard_inset_base_padding_bottom, contentAboveBar.getPaddingBottom());
        }
        if (bottomBar == null) {
            return;
        }
        bottomBar.setTag(R.id.keyboard_inset_manual_visible, visible);
        bottomBar.setTag(R.id.keyboard_inset_fallback_height, dp(activity, fallbackKeyboardHeightDp));
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content != null && content.getChildCount() > 0) {
            applyKeyboardOffset(content.getChildAt(0), bottomBar, contentAboveBar, dp(activity, 96), 0, dp(activity, fallbackKeyboardHeightDp));
        }
    }

    static void setManualKeyboardVisible(Activity activity, View bottomBar, boolean visible, int fallbackKeyboardHeightDp) {
        setManualKeyboardVisible(activity, bottomBar, null, visible, fallbackKeyboardHeightDp);
    }

    private static void applyKeyboardOffset(View root, View bottomBar, View contentAboveBar, int keyboardThreshold, int imeBottomInset, int fallbackKeyboardHeight) {
        int rootHeight = root.getRootView().getHeight();
        int keyboardTop = rootHeight;
        boolean keyboardDetected = false;
        if (imeBottomInset > keyboardThreshold) {
            keyboardTop = Math.min(keyboardTop, rootHeight - imeBottomInset);
            keyboardDetected = true;
        }
        Rect visibleFrame = new Rect();
        root.getWindowVisibleDisplayFrame(visibleFrame);
        if (visibleFrame.bottom > 0 && rootHeight - visibleFrame.bottom > keyboardThreshold) {
            keyboardTop = Math.min(keyboardTop, visibleFrame.bottom);
            keyboardDetected = true;
        }
        int[] location = new int[2];
        bottomBar.getLocationOnScreen(location);
        int bottomBarBottom = location[1] + bottomBar.getHeight() + getCurrentExtraBottomMargin(bottomBar);
        int overlap = keyboardDetected ? Math.max(0, bottomBarBottom - keyboardTop) : 0;
        if (Boolean.TRUE.equals(bottomBar.getTag(R.id.keyboard_inset_manual_visible))) {
            if (keyboardDetected) {
                overlap = Math.max(0, bottomBarBottom - keyboardTop);
            } else {
                Object taggedHeight = bottomBar.getTag(R.id.keyboard_inset_fallback_height);
                int manualHeight = taggedHeight instanceof Integer ? (Integer) taggedHeight : fallbackKeyboardHeight;
                overlap = Math.max(0, manualHeight);
            }
        }
        bottomBar.setTranslationY(0f);
        applyBottomBarMargin(bottomBar, overlap);
        applyContentBottomPadding(contentAboveBar, overlap);
    }

    private static int getCurrentExtraBottomMargin(View bottomBar) {
        ViewGroup.LayoutParams params = bottomBar.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return 0;
        }
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        int baseBottom = getTaggedInt(bottomBar, R.id.keyboard_inset_base_margin_bottom, margins.bottomMargin);
        return Math.max(0, margins.bottomMargin - baseBottom);
    }

    private static void saveBaseMargins(View view) {
        if (view.getTag(R.id.keyboard_inset_base_margin_bottom) != null) {
            return;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            view.setTag(R.id.keyboard_inset_base_margin_left, margins.leftMargin);
            view.setTag(R.id.keyboard_inset_base_margin_top, margins.topMargin);
            view.setTag(R.id.keyboard_inset_base_margin_right, margins.rightMargin);
            view.setTag(R.id.keyboard_inset_base_margin_bottom, margins.bottomMargin);
        } else {
            view.setTag(R.id.keyboard_inset_base_margin_left, 0);
            view.setTag(R.id.keyboard_inset_base_margin_top, 0);
            view.setTag(R.id.keyboard_inset_base_margin_right, 0);
            view.setTag(R.id.keyboard_inset_base_margin_bottom, 0);
        }
    }

    private static void applyBottomBarMargin(View bottomBar, int keyboardOverlap) {
        ViewGroup.LayoutParams params = bottomBar.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        int baseLeft = getTaggedInt(bottomBar, R.id.keyboard_inset_base_margin_left, margins.leftMargin);
        int baseTop = getTaggedInt(bottomBar, R.id.keyboard_inset_base_margin_top, margins.topMargin);
        int baseRight = getTaggedInt(bottomBar, R.id.keyboard_inset_base_margin_right, margins.rightMargin);
        int baseBottom = getTaggedInt(bottomBar, R.id.keyboard_inset_base_margin_bottom, margins.bottomMargin);
        int targetBottom = baseBottom + keyboardOverlap;
        if (margins.leftMargin != baseLeft
                || margins.topMargin != baseTop
                || margins.rightMargin != baseRight
                || margins.bottomMargin != targetBottom) {
            margins.setMargins(baseLeft, baseTop, baseRight, targetBottom);
            bottomBar.setLayoutParams(margins);
        }
    }

    private static void applyContentBottomPadding(View contentAboveBar, int keyboardOverlap) {
        if (contentAboveBar == null) {
            return;
        }
        int baseLeft = getTaggedInt(contentAboveBar, R.id.keyboard_inset_base_padding_left, contentAboveBar.getPaddingLeft());
        int baseTop = getTaggedInt(contentAboveBar, R.id.keyboard_inset_base_padding_top, contentAboveBar.getPaddingTop());
        int baseRight = getTaggedInt(contentAboveBar, R.id.keyboard_inset_base_padding_right, contentAboveBar.getPaddingRight());
        int baseBottom = getTaggedInt(contentAboveBar, R.id.keyboard_inset_base_padding_bottom, contentAboveBar.getPaddingBottom());
        int targetBottom = baseBottom + keyboardOverlap;
        if (contentAboveBar.getPaddingLeft() != baseLeft
                || contentAboveBar.getPaddingTop() != baseTop
                || contentAboveBar.getPaddingRight() != baseRight
                || contentAboveBar.getPaddingBottom() != targetBottom) {
            contentAboveBar.setPadding(baseLeft, baseTop, baseRight, targetBottom);
        }
    }

    private static void applyTopBarPadding(View topBar, int statusBarHeight) {
        if (statusBarHeight <= 0) {
            return;
        }
        int[] location = new int[2];
        topBar.getLocationOnScreen(location);
        int overlap = Math.max(0, statusBarHeight - Math.max(0, location[1]));
        int baseLeft = getTaggedInt(topBar, R.id.keyboard_inset_base_padding_left, topBar.getPaddingLeft());
        int baseTop = getTaggedInt(topBar, R.id.keyboard_inset_base_padding_top, topBar.getPaddingTop());
        int baseRight = getTaggedInt(topBar, R.id.keyboard_inset_base_padding_right, topBar.getPaddingRight());
        int baseBottom = getTaggedInt(topBar, R.id.keyboard_inset_base_padding_bottom, topBar.getPaddingBottom());
        int targetTop = baseTop + overlap;
        if (topBar.getPaddingLeft() != baseLeft
                || topBar.getPaddingTop() != targetTop
                || topBar.getPaddingRight() != baseRight
                || topBar.getPaddingBottom() != baseBottom) {
            topBar.setPadding(baseLeft, targetTop, baseRight, baseBottom);
        }
    }

    private static void applySystemBarPadding(View rootView, int statusBarHeight, int navigationBarHeight) {
        int baseLeft = getTaggedInt(rootView, R.id.keyboard_inset_base_padding_left, rootView.getPaddingLeft());
        int baseTop = getTaggedInt(rootView, R.id.keyboard_inset_base_padding_top, rootView.getPaddingTop());
        int baseRight = getTaggedInt(rootView, R.id.keyboard_inset_base_padding_right, rootView.getPaddingRight());
        int baseBottom = getTaggedInt(rootView, R.id.keyboard_inset_base_padding_bottom, rootView.getPaddingBottom());
        int targetTop = baseTop + Math.max(0, statusBarHeight);
        int targetBottom = baseBottom + Math.max(0, navigationBarHeight);
        if (rootView.getPaddingLeft() != baseLeft
                || rootView.getPaddingTop() != targetTop
                || rootView.getPaddingRight() != baseRight
                || rootView.getPaddingBottom() != targetBottom) {
            rootView.setPadding(baseLeft, targetTop, baseRight, targetBottom);
        }
    }

    private static int getTaggedInt(View view, int tagId, int fallback) {
        Object tagged = view.getTag(tagId);
        return tagged instanceof Integer ? (Integer) tagged : fallback;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int getStatusBarHeight(Activity activity) {
        int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId <= 0) {
            return 0;
        }
        return activity.getResources().getDimensionPixelSize(resourceId);
    }

    private static int getNavigationBarHeight(Activity activity) {
        int resourceId = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId <= 0) {
            return 0;
        }
        return activity.getResources().getDimensionPixelSize(resourceId);
    }
}
