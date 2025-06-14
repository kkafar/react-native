/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.views.text;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LayoutDirection;
import android.view.Gravity;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Preconditions;
import com.facebook.common.logging.FLog;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.annotations.UnstableReactNativeAPI;
import com.facebook.react.common.mapbuffer.MapBuffer;
import com.facebook.react.common.mapbuffer.ReadableMapBuffer;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.ReactAccessibilityDelegate.AccessibilityRole;
import com.facebook.react.uimanager.ReactAccessibilityDelegate.Role;
import com.facebook.react.views.text.internal.span.CustomLetterSpacingSpan;
import com.facebook.react.views.text.internal.span.CustomLineHeightSpan;
import com.facebook.react.views.text.internal.span.CustomStyleSpan;
import com.facebook.react.views.text.internal.span.ReactAbsoluteSizeSpan;
import com.facebook.react.views.text.internal.span.ReactBackgroundColorSpan;
import com.facebook.react.views.text.internal.span.ReactClickableSpan;
import com.facebook.react.views.text.internal.span.ReactForegroundColorSpan;
import com.facebook.react.views.text.internal.span.ReactOpacitySpan;
import com.facebook.react.views.text.internal.span.ReactStrikethroughSpan;
import com.facebook.react.views.text.internal.span.ReactTagSpan;
import com.facebook.react.views.text.internal.span.ReactTextPaintHolderSpan;
import com.facebook.react.views.text.internal.span.ReactUnderlineSpan;
import com.facebook.react.views.text.internal.span.SetSpanOperation;
import com.facebook.react.views.text.internal.span.ShadowStyleSpan;
import com.facebook.react.views.text.internal.span.TextInlineViewPlaceholderSpan;
import com.facebook.yoga.YogaMeasureMode;
import com.facebook.yoga.YogaMeasureOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Class responsible of creating {@link Spanned} object for the JS representation of Text */
public class TextLayoutManager {

  // constants for AttributedString serialization
  public static final short AS_KEY_HASH = 0;
  public static final short AS_KEY_STRING = 1;
  public static final short AS_KEY_FRAGMENTS = 2;
  public static final short AS_KEY_CACHE_ID = 3;
  public static final short AS_KEY_BASE_ATTRIBUTES = 4;

  // constants for Fragment serialization
  public static final short FR_KEY_STRING = 0;
  public static final short FR_KEY_REACT_TAG = 1;
  public static final short FR_KEY_IS_ATTACHMENT = 2;
  public static final short FR_KEY_WIDTH = 3;
  public static final short FR_KEY_HEIGHT = 4;
  public static final short FR_KEY_TEXT_ATTRIBUTES = 5;

  // constants for ParagraphAttributes serialization
  public static final short PA_KEY_MAX_NUMBER_OF_LINES = 0;
  public static final short PA_KEY_ELLIPSIZE_MODE = 1;
  public static final short PA_KEY_TEXT_BREAK_STRATEGY = 2;
  public static final short PA_KEY_ADJUST_FONT_SIZE_TO_FIT = 3;
  public static final short PA_KEY_INCLUDE_FONT_PADDING = 4;
  public static final short PA_KEY_HYPHENATION_FREQUENCY = 5;
  public static final short PA_KEY_MINIMUM_FONT_SIZE = 6;
  public static final short PA_KEY_MAXIMUM_FONT_SIZE = 7;
  public static final short PA_KEY_TEXT_ALIGN_VERTICAL = 8;

  private static final String TAG = TextLayoutManager.class.getSimpleName();

  // Each thread has its own copy of scratch TextPaint so that TextLayoutManager
  // measurement/Spannable creation can be free-threaded.
  private static final ThreadLocal<TextPaint> sTextPaintInstance =
      new ThreadLocal<TextPaint>() {
        @Override
        protected TextPaint initialValue() {
          return new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        }
      };

  private static final String INLINE_VIEW_PLACEHOLDER = "0";

  private static final boolean DEFAULT_INCLUDE_FONT_PADDING = true;

  private static final boolean DEFAULT_ADJUST_FONT_SIZE_TO_FIT = false;

  private static final ConcurrentHashMap<Integer, Spannable> sTagToSpannableCache =
      new ConcurrentHashMap<>();

  public static void setCachedSpannableForTag(int reactTag, @NonNull Spannable sp) {
    sTagToSpannableCache.put(reactTag, sp);
  }

  public static void deleteCachedSpannableForTag(int reactTag) {
    sTagToSpannableCache.remove(reactTag);
  }

  public static boolean isRTL(MapBuffer attributedString) {
    // TODO: Don't read AS_KEY_FRAGMENTS, which may be expensive, and is not present when using
    // cached Spannable
    if (!attributedString.contains(AS_KEY_FRAGMENTS)) {
      return false;
    }

    MapBuffer fragments = attributedString.getMapBuffer(AS_KEY_FRAGMENTS);
    if (fragments.getCount() == 0) {
      return false;
    }

    MapBuffer fragment = fragments.getMapBuffer(0);
    MapBuffer textAttributes = fragment.getMapBuffer(FR_KEY_TEXT_ATTRIBUTES);

    if (!textAttributes.contains(TextAttributeProps.TA_KEY_LAYOUT_DIRECTION)) {
      return false;
    }

    return TextAttributeProps.getLayoutDirection(
            textAttributes.getString(TextAttributeProps.TA_KEY_LAYOUT_DIRECTION))
        == LayoutDirection.RTL;
  }

  @Nullable
  private static String getTextAlignmentAttr(MapBuffer attributedString) {
    // TODO: Don't read AS_KEY_FRAGMENTS, which may be expensive, and is not present when using
    // cached Spannable
    if (!attributedString.contains(AS_KEY_FRAGMENTS)) {
      return null;
    }

    MapBuffer fragments = attributedString.getMapBuffer(AS_KEY_FRAGMENTS);
    if (fragments.getCount() != 0) {
      MapBuffer fragment = fragments.getMapBuffer(0);
      MapBuffer textAttributes = fragment.getMapBuffer(FR_KEY_TEXT_ATTRIBUTES);

      if (textAttributes.contains(TextAttributeProps.TA_KEY_ALIGNMENT)) {
        return textAttributes.getString(TextAttributeProps.TA_KEY_ALIGNMENT);
      }
    }

    return null;
  }

  private static int getTextJustificationMode(@Nullable String alignmentAttr) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return -1;
    }

    if (alignmentAttr != null && alignmentAttr.equals("justified")) {
      return Layout.JUSTIFICATION_MODE_INTER_WORD;
    }

    return Layout.JUSTIFICATION_MODE_NONE;
  }

  private static Layout.Alignment getTextAlignment(
      MapBuffer attributedString, Spannable spanned, @Nullable String alignmentAttr) {
    // Android will align text based on the script, so normal and opposite alignment needs to be
    // swapped when the directions of paragraph and script don't match.
    // I.e. paragraph is LTR but script is RTL, text needs to be aligned to the left, which means
    // ALIGN_OPPOSITE needs to be used to align RTL script to the left
    boolean isParagraphRTL = isRTL(attributedString);
    boolean isScriptRTL =
        TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(spanned, 0, spanned.length());
    boolean swapNormalAndOpposite = isParagraphRTL != isScriptRTL;

    Layout.Alignment alignment =
        swapNormalAndOpposite ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;

    if (alignmentAttr == null) {
      return alignment;
    }

    if (alignmentAttr.equals("center")) {
      alignment = Layout.Alignment.ALIGN_CENTER;
    } else if (alignmentAttr.equals("right")) {
      alignment =
          swapNormalAndOpposite ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
    }

    return alignment;
  }

  public static int getTextGravity(
      MapBuffer attributedString, Spannable spanned, int defaultValue) {
    int gravity = defaultValue;
    @Nullable String alignmentAttr = getTextAlignmentAttr(attributedString);
    Layout.Alignment alignment = getTextAlignment(attributedString, spanned, alignmentAttr);

    // depending on whether the script is LTR or RTL, ALIGN_NORMAL and ALIGN_OPPOSITE may mean
    // different things
    boolean swapLeftAndRight =
        TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(spanned, 0, spanned.length());

    if (alignment == Layout.Alignment.ALIGN_NORMAL) {
      gravity = swapLeftAndRight ? Gravity.RIGHT : Gravity.LEFT;
    } else if (alignment == Layout.Alignment.ALIGN_OPPOSITE) {
      gravity = swapLeftAndRight ? Gravity.LEFT : Gravity.RIGHT;
    } else if (alignment == Layout.Alignment.ALIGN_CENTER) {
      gravity = Gravity.CENTER_HORIZONTAL;
    }

    return gravity;
  }

  private static void buildSpannableFromFragments(
      Context context, MapBuffer fragments, SpannableStringBuilder sb, List<SetSpanOperation> ops) {

    for (int i = 0, length = fragments.getCount(); i < length; i++) {
      MapBuffer fragment = fragments.getMapBuffer(i);
      int start = sb.length();

      TextAttributeProps textAttributes =
          TextAttributeProps.fromMapBuffer(fragment.getMapBuffer(FR_KEY_TEXT_ATTRIBUTES));

      sb.append(
          TextTransform.apply(fragment.getString(FR_KEY_STRING), textAttributes.mTextTransform));

      int end = sb.length();
      int reactTag =
          fragment.contains(FR_KEY_REACT_TAG) ? fragment.getInt(FR_KEY_REACT_TAG) : View.NO_ID;
      if (fragment.contains(FR_KEY_IS_ATTACHMENT) && fragment.getBoolean(FR_KEY_IS_ATTACHMENT)) {
        float width = PixelUtil.toPixelFromSP(fragment.getDouble(FR_KEY_WIDTH));
        float height = PixelUtil.toPixelFromSP(fragment.getDouble(FR_KEY_HEIGHT));
        ops.add(
            new SetSpanOperation(
                sb.length() - INLINE_VIEW_PLACEHOLDER.length(),
                sb.length(),
                new TextInlineViewPlaceholderSpan(reactTag, (int) width, (int) height)));
      } else if (end >= start) {
        boolean roleIsLink =
            textAttributes.mRole != null
                ? textAttributes.mRole == Role.LINK
                : textAttributes.mAccessibilityRole == AccessibilityRole.LINK;
        if (roleIsLink) {
          ops.add(new SetSpanOperation(start, end, new ReactClickableSpan(reactTag)));
        }
        if (textAttributes.mIsColorSet) {
          ops.add(
              new SetSpanOperation(
                  start, end, new ReactForegroundColorSpan(textAttributes.mColor)));
        }
        if (textAttributes.mIsBackgroundColorSet) {
          ops.add(
              new SetSpanOperation(
                  start, end, new ReactBackgroundColorSpan(textAttributes.mBackgroundColor)));
        }
        if (!Float.isNaN(textAttributes.getOpacity())) {
          ops.add(
              new SetSpanOperation(start, end, new ReactOpacitySpan(textAttributes.getOpacity())));
        }
        if (!Float.isNaN(textAttributes.getLetterSpacing())) {
          ops.add(
              new SetSpanOperation(
                  start, end, new CustomLetterSpacingSpan(textAttributes.getLetterSpacing())));
        }
        ops.add(
            new SetSpanOperation(start, end, new ReactAbsoluteSizeSpan(textAttributes.mFontSize)));
        if (textAttributes.mFontStyle != ReactConstants.UNSET
            || textAttributes.mFontWeight != ReactConstants.UNSET
            || textAttributes.mFontFamily != null) {
          ops.add(
              new SetSpanOperation(
                  start,
                  end,
                  new CustomStyleSpan(
                      textAttributes.mFontStyle,
                      textAttributes.mFontWeight,
                      textAttributes.mFontFeatureSettings,
                      textAttributes.mFontFamily,
                      context.getAssets())));
        }
        if (textAttributes.mIsUnderlineTextDecorationSet) {
          ops.add(new SetSpanOperation(start, end, new ReactUnderlineSpan()));
        }
        if (textAttributes.mIsLineThroughTextDecorationSet) {
          ops.add(new SetSpanOperation(start, end, new ReactStrikethroughSpan()));
        }
        if ((textAttributes.mTextShadowOffsetDx != 0
                || textAttributes.mTextShadowOffsetDy != 0
                || textAttributes.mTextShadowRadius != 0)
            && Color.alpha(textAttributes.mTextShadowColor) != 0) {
          ops.add(
              new SetSpanOperation(
                  start,
                  end,
                  new ShadowStyleSpan(
                      textAttributes.mTextShadowOffsetDx,
                      textAttributes.mTextShadowOffsetDy,
                      textAttributes.mTextShadowRadius,
                      textAttributes.mTextShadowColor)));
        }
        if (!Float.isNaN(textAttributes.getEffectiveLineHeight())) {
          ops.add(
              new SetSpanOperation(
                  start, end, new CustomLineHeightSpan(textAttributes.getEffectiveLineHeight())));
        }

        ops.add(new SetSpanOperation(start, end, new ReactTagSpan(reactTag)));
      }
    }
  }

  // public because both ReactTextViewManager and ReactTextInputManager need to use this
  public static Spannable getOrCreateSpannableForText(
      Context context,
      MapBuffer attributedString,
      @Nullable ReactTextViewManagerCallback reactTextViewManagerCallback) {
    Spannable text = null;
    if (attributedString.contains(AS_KEY_CACHE_ID)) {
      Integer cacheId = attributedString.getInt(AS_KEY_CACHE_ID);
      text = sTagToSpannableCache.get(cacheId);
    } else {
      text =
          createSpannableFromAttributedString(
              context, attributedString, reactTextViewManagerCallback);
    }

    return text;
  }

  private static Spannable createSpannableFromAttributedString(
      Context context,
      MapBuffer attributedString,
      @Nullable ReactTextViewManagerCallback reactTextViewManagerCallback) {

    SpannableStringBuilder sb = new SpannableStringBuilder();

    // The {@link SpannableStringBuilder} implementation require setSpan operation to be called
    // up-to-bottom, otherwise all the spannables that are within the region for which one may set
    // a new spannable will be wiped out
    List<SetSpanOperation> ops = new ArrayList<>();

    buildSpannableFromFragments(context, attributedString.getMapBuffer(AS_KEY_FRAGMENTS), sb, ops);

    // TODO T31905686: add support for inline Images
    // While setting the Spans on the final text, we also check whether any of them are images.
    for (int priorityIndex = 0; priorityIndex < ops.size(); ++priorityIndex) {
      final SetSpanOperation op = ops.get(ops.size() - priorityIndex - 1);

      // Actual order of calling {@code execute} does NOT matter,
      // but the {@code priorityIndex} DOES matter.
      op.execute(sb, priorityIndex);
    }

    if (reactTextViewManagerCallback != null) {
      reactTextViewManagerCallback.onPostProcessSpannable(sb);
    }
    return sb;
  }

  private static Layout createLayout(
      Spannable text,
      @Nullable BoringLayout.Metrics boring,
      float width,
      YogaMeasureMode widthYogaMeasureMode,
      boolean includeFontPadding,
      int textBreakStrategy,
      int hyphenationFrequency,
      Layout.Alignment alignment,
      int justificationMode,
      @Nullable TextUtils.TruncateAt ellipsizeMode,
      int maxNumberOfLines,
      TextPaint paint) {
    // If our text is boring, and fully fits in the available space, we can represent the text
    // layout as a BoringLayout
    if (boring != null
        && (widthYogaMeasureMode == YogaMeasureMode.UNDEFINED
            || boring.width <= Math.floor(width))) {
      int layoutWidth =
          widthYogaMeasureMode == YogaMeasureMode.EXACTLY ? (int) Math.floor(width) : boring.width;
      return BoringLayout.make(
          text, paint, layoutWidth, alignment, 1.f, 0.f, boring, includeFontPadding);
    }

    int desiredWidth = (int) Math.ceil(Layout.getDesiredWidth(text, paint));

    int layoutWidth =
        widthYogaMeasureMode == YogaMeasureMode.EXACTLY
            ? (int) Math.floor(width)
            : widthYogaMeasureMode == YogaMeasureMode.UNDEFINED
                ? desiredWidth
                : Math.min(desiredWidth, (int) Math.floor(width));

    StaticLayout.Builder builder =
        StaticLayout.Builder.obtain(text, 0, text.length(), paint, layoutWidth)
            .setAlignment(alignment)
            .setLineSpacing(0.f, 1.f)
            .setIncludePad(includeFontPadding)
            .setBreakStrategy(textBreakStrategy)
            .setHyphenationFrequency(hyphenationFrequency);

    if (maxNumberOfLines != ReactConstants.UNSET && maxNumberOfLines != 0) {
      builder.setEllipsize(ellipsizeMode).setMaxLines(maxNumberOfLines);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      builder.setJustificationMode(justificationMode);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      builder.setUseLineSpacingFromFallbacks(true);
    }

    return builder.build();
  }

  /**
   * Sets attributes on the TextPaint, used for content outside the Spannable text, like for empty
   * strings, or newlines after the last trailing character
   */
  private static void updateTextPaint(
      TextPaint paint, TextAttributeProps baseTextAttributes, Context context) {
    if (baseTextAttributes.getEffectiveFontSize() != ReactConstants.UNSET) {
      paint.setTextSize(baseTextAttributes.getEffectiveFontSize());
    }

    if (baseTextAttributes.getFontStyle() != ReactConstants.UNSET
        || baseTextAttributes.getFontWeight() != ReactConstants.UNSET
        || baseTextAttributes.getFontFamily() != null) {
      Typeface typeface =
          ReactTypefaceUtils.applyStyles(
              null,
              baseTextAttributes.getFontStyle(),
              baseTextAttributes.getFontWeight(),
              baseTextAttributes.getFontFamily(),
              context.getAssets());
      paint.setTypeface(typeface);

      if (baseTextAttributes.getFontStyle() != ReactConstants.UNSET
          && baseTextAttributes.getFontStyle() != typeface.getStyle()) {
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/widget/TextView.java;l=2536;drc=d262a68a1e0c3b640274b094a7f1e3a5b75563e9
        int missingStyle = baseTextAttributes.getFontStyle() & ~typeface.getStyle();
        paint.setFakeBoldText((missingStyle & Typeface.BOLD) != 0);
        paint.setTextSkewX((missingStyle & Typeface.ITALIC) != 0 ? -0.25f : 0);
      }
    }
  }

  /**
   * WARNING: This paint should not be used for any layouts which may escape TextLayoutManager, as
   * they may need to be drawn later, and may not safely be reused
   */
  private static TextPaint scratchPaintWithAttributes(
      TextAttributeProps baseTextAttributes, Context context) {
    TextPaint paint = Preconditions.checkNotNull(sTextPaintInstance.get());
    paint.setTypeface(null);
    paint.setTextSize(12);
    paint.setFakeBoldText(false);
    paint.setTextSkewX(0);
    updateTextPaint(paint, baseTextAttributes, context);
    return paint;
  }

  private static TextPaint newPaintWithAttributes(
      TextAttributeProps baseTextAttributes, Context context) {
    TextPaint paint = new TextPaint();
    updateTextPaint(paint, baseTextAttributes, context);
    return paint;
  }

  private static Layout createLayoutForMeasurement(
      Context context,
      MapBuffer attributedString,
      MapBuffer paragraphAttributes,
      float width,
      YogaMeasureMode widthYogaMeasureMode,
      float height,
      YogaMeasureMode heightYogaMeasureMode,
      @Nullable ReactTextViewManagerCallback reactTextViewManagerCallback) {
    Spannable text =
        getOrCreateSpannableForText(context, attributedString, reactTextViewManagerCallback);

    TextPaint paint;
    if (attributedString.contains(AS_KEY_CACHE_ID)) {
      paint = text.getSpans(0, 0, ReactTextPaintHolderSpan.class)[0].getTextPaint();
    } else {
      TextAttributeProps baseTextAttributes =
          TextAttributeProps.fromMapBuffer(attributedString.getMapBuffer(AS_KEY_BASE_ATTRIBUTES));
      paint = scratchPaintWithAttributes(baseTextAttributes, context);
    }

    return createLayout(
        text,
        paint,
        attributedString,
        paragraphAttributes,
        width,
        widthYogaMeasureMode,
        height,
        heightYogaMeasureMode);
  }

  private static Layout createLayout(
      Spannable text,
      TextPaint paint,
      MapBuffer attributedString,
      MapBuffer paragraphAttributes,
      float width,
      YogaMeasureMode widthYogaMeasureMode,
      float height,
      YogaMeasureMode heightYogaMeasureMode) {
    BoringLayout.Metrics boring = isBoring(text, paint);

    int textBreakStrategy =
        TextAttributeProps.getTextBreakStrategy(
            paragraphAttributes.getString(PA_KEY_TEXT_BREAK_STRATEGY));
    boolean includeFontPadding =
        paragraphAttributes.contains(PA_KEY_INCLUDE_FONT_PADDING)
            ? paragraphAttributes.getBoolean(PA_KEY_INCLUDE_FONT_PADDING)
            : DEFAULT_INCLUDE_FONT_PADDING;
    int hyphenationFrequency =
        TextAttributeProps.getHyphenationFrequency(
            paragraphAttributes.getString(PA_KEY_HYPHENATION_FREQUENCY));
    boolean adjustFontSizeToFit =
        paragraphAttributes.contains(PA_KEY_ADJUST_FONT_SIZE_TO_FIT)
            ? paragraphAttributes.getBoolean(PA_KEY_ADJUST_FONT_SIZE_TO_FIT)
            : DEFAULT_ADJUST_FONT_SIZE_TO_FIT;
    int maximumNumberOfLines =
        paragraphAttributes.contains(PA_KEY_MAX_NUMBER_OF_LINES)
            ? paragraphAttributes.getInt(PA_KEY_MAX_NUMBER_OF_LINES)
            : ReactConstants.UNSET;
    @Nullable
    TextUtils.TruncateAt ellipsizeMode =
        paragraphAttributes.contains(PA_KEY_ELLIPSIZE_MODE)
            ? TextAttributeProps.getEllipsizeMode(
                paragraphAttributes.getString(PA_KEY_ELLIPSIZE_MODE))
            : null;

    // T226571629: textAlign should be moved to ParagraphAttributes
    @Nullable String alignmentAttr = getTextAlignmentAttr(attributedString);
    Layout.Alignment alignment = getTextAlignment(attributedString, text, alignmentAttr);
    int justificationMode = getTextJustificationMode(alignmentAttr);

    if (adjustFontSizeToFit) {
      double minimumFontSize =
          paragraphAttributes.contains(PA_KEY_MINIMUM_FONT_SIZE)
              ? paragraphAttributes.getDouble(PA_KEY_MINIMUM_FONT_SIZE)
              : Double.NaN;

      adjustSpannableFontToFit(
          text,
          width,
          YogaMeasureMode.EXACTLY,
          height,
          heightYogaMeasureMode,
          minimumFontSize,
          maximumNumberOfLines,
          includeFontPadding,
          textBreakStrategy,
          hyphenationFrequency,
          alignment,
          justificationMode,
          paint);
    }

    return createLayout(
        text,
        boring,
        width,
        widthYogaMeasureMode,
        includeFontPadding,
        textBreakStrategy,
        hyphenationFrequency,
        alignment,
        justificationMode,
        ellipsizeMode,
        maximumNumberOfLines,
        paint);
  }

  @UnstableReactNativeAPI
  public static PreparedLayout createPreparedLayout(
      Context context,
      ReadableMapBuffer attributedString,
      ReadableMapBuffer paragraphAttributes,
      float width,
      YogaMeasureMode widthYogaMeasureMode,
      float height,
      YogaMeasureMode heightYogaMeasureMode,
      @Nullable ReactTextViewManagerCallback reactTextViewManagerCallback) {
    Spannable text =
        getOrCreateSpannableForText(context, attributedString, reactTextViewManagerCallback);
    TextAttributeProps baseTextAttributes =
        TextAttributeProps.fromMapBuffer(attributedString.getMapBuffer(AS_KEY_BASE_ATTRIBUTES));
    Layout layout =
        TextLayoutManager.createLayout(
            text,
            newPaintWithAttributes(baseTextAttributes, context),
            attributedString,
            paragraphAttributes,
            width,
            widthYogaMeasureMode,
            height,
            heightYogaMeasureMode);

    int maximumNumberOfLines =
        paragraphAttributes.contains(TextLayoutManager.PA_KEY_MAX_NUMBER_OF_LINES)
            ? paragraphAttributes.getInt(TextLayoutManager.PA_KEY_MAX_NUMBER_OF_LINES)
            : ReactConstants.UNSET;

    float verticalOffset =
        getVerticalOffset(
            layout, paragraphAttributes, height, heightYogaMeasureMode, maximumNumberOfLines);

    return new PreparedLayout(layout, maximumNumberOfLines, verticalOffset);
  }

  /*package*/ static void adjustSpannableFontToFit(
      Spannable text,
      float width,
      YogaMeasureMode widthYogaMeasureMode,
      float height,
      YogaMeasureMode heightYogaMeasureMode,
      double minimumFontSizeAttr,
      int maximumNumberOfLines,
      boolean includeFontPadding,
      int textBreakStrategy,
      int hyphenationFrequency,
      Layout.Alignment alignment,
      int justificationMode,
      TextPaint paint) {
    BoringLayout.Metrics boring = isBoring(text, paint);
    Layout layout =
        createLayout(
            text,
            boring,
            width,
            widthYogaMeasureMode,
            includeFontPadding,
            textBreakStrategy,
            hyphenationFrequency,
            alignment,
            justificationMode,
            null,
            ReactConstants.UNSET,
            paint);

    // Minimum font size is 4pts to match the iOS implementation.
    int minimumFontSize =
        (int)
            (Double.isNaN(minimumFontSizeAttr) ? PixelUtil.toPixelFromDIP(4) : minimumFontSizeAttr);

    // Find the largest font size used in the spannable to use as a starting point.
    int currentFontSize = minimumFontSize;
    ReactAbsoluteSizeSpan[] spans = text.getSpans(0, text.length(), ReactAbsoluteSizeSpan.class);
    for (ReactAbsoluteSizeSpan span : spans) {
      currentFontSize = Math.max(currentFontSize, span.getSize());
    }

    int initialFontSize = currentFontSize;
    while (currentFontSize > minimumFontSize
        && ((maximumNumberOfLines != ReactConstants.UNSET
                && maximumNumberOfLines != 0
                && layout.getLineCount() > maximumNumberOfLines)
            || (heightYogaMeasureMode != YogaMeasureMode.UNDEFINED && layout.getHeight() > height)
            || (text.length() == 1 && layout.getLineWidth(0) > width))) {
      // TODO: We could probably use a smarter algorithm here. This will require 0(n)
      // measurements based on the number of points the font size needs to be reduced by.
      currentFontSize -= Math.max(1, (int) PixelUtil.toPixelFromDIP(1));

      float ratio = (float) currentFontSize / (float) initialFontSize;
      paint.setTextSize(Math.max((paint.getTextSize() * ratio), minimumFontSize));

      ReactAbsoluteSizeSpan[] sizeSpans =
          text.getSpans(0, text.length(), ReactAbsoluteSizeSpan.class);
      for (ReactAbsoluteSizeSpan span : sizeSpans) {
        text.setSpan(
            new ReactAbsoluteSizeSpan((int) Math.max((span.getSize() * ratio), minimumFontSize)),
            text.getSpanStart(span),
            text.getSpanEnd(span),
            text.getSpanFlags(span));
        text.removeSpan(span);
      }
      if (boring != null) {
        boring = isBoring(text, paint);
      }
      layout =
          createLayout(
              text,
              boring,
              width,
              widthYogaMeasureMode,
              includeFontPadding,
              textBreakStrategy,
              hyphenationFrequency,
              alignment,
              justificationMode,
              null,
              ReactConstants.UNSET,
              paint);
    }
  }

  public static long measureText(
      Context context,
      MapBuffer attributedString,
      MapBuffer paragraphAttributes,
      float width,
      YogaMeasureMode widthYogaMeasureMode,
      float height,
      YogaMeasureMode heightYogaMeasureMode,
      @Nullable ReactTextViewManagerCallback reactTextViewManagerCallback,
      @Nullable float[] attachmentsPositions) {
    // TODO(5578671): Handle text direction (see View#getTextDirectionHeuristic)
    Layout layout =
        createLayoutForMeasurement(
            context,
            attributedString,
            paragraphAttributes,
            width,
            widthYogaMeasureMode,
            height,
            heightYogaMeasureMode,
            reactTextViewManagerCallback);

    int maximumNumberOfLines =
        paragraphAttributes.contains(PA_KEY_MAX_NUMBER_OF_LINES)
            ? paragraphAttributes.getInt(PA_KEY_MAX_NUMBER_OF_LINES)
            : ReactConstants.UNSET;

    Spanned text = (Spanned) layout.getText();

    int calculatedLineCount = calculateLineCount(layout, maximumNumberOfLines);
    float calculatedWidth =
        calculateWidth(layout, text, width, widthYogaMeasureMode, calculatedLineCount);
    float calculatedHeight =
        calculateHeight(layout, height, heightYogaMeasureMode, calculatedLineCount);

    if (attachmentsPositions != null) {
      int attachmentIndex = 0;
      int lastAttachmentFoundInSpan;

      AttachmentMetrics metrics = new AttachmentMetrics();
      for (int i = 0; i < text.length(); i = lastAttachmentFoundInSpan) {
        lastAttachmentFoundInSpan =
            nextAttachmentMetrics(
                layout, text, calculatedWidth, calculatedLineCount, i, 0, metrics);
        if (metrics.wasFound) {
          attachmentsPositions[attachmentIndex] = PixelUtil.toDIPFromPixel(metrics.top);
          attachmentsPositions[attachmentIndex + 1] = PixelUtil.toDIPFromPixel(metrics.left);
          attachmentIndex += 2;
        }
      }
    }

    float widthInSP = PixelUtil.toDIPFromPixel(calculatedWidth);
    float heightInSP = PixelUtil.toDIPFromPixel(calculatedHeight);

    return YogaMeasureOutput.make(widthInSP, heightInSP);
  }

  @UnstableReactNativeAPI
  public static float[] measurePreparedLayout(
      PreparedLayout preparedLayout,
      float width,
      YogaMeasureMode widthYogaMeasureMode,
      float height,
      YogaMeasureMode heightYogaMeasureMode) {
    Layout layout = preparedLayout.getLayout();
    Spanned text = (Spanned) layout.getText();
    int maximumNumberOfLines = preparedLayout.getMaximumNumberOfLines();

    int calculatedLineCount = calculateLineCount(layout, maximumNumberOfLines);
    float calculatedWidth =
        calculateWidth(layout, text, width, widthYogaMeasureMode, calculatedLineCount);
    float calculatedHeight =
        calculateHeight(layout, height, heightYogaMeasureMode, calculatedLineCount);

    ArrayList<Float> retList = new ArrayList<>();
    retList.add(PixelUtil.toDIPFromPixel(calculatedWidth));
    retList.add(PixelUtil.toDIPFromPixel(calculatedHeight));

    AttachmentMetrics metrics = new AttachmentMetrics();
    int lastAttachmentFoundInSpan;
    for (int i = 0; i < text.length(); i = lastAttachmentFoundInSpan) {
      lastAttachmentFoundInSpan =
          nextAttachmentMetrics(
              layout,
              text,
              calculatedWidth,
              calculatedLineCount,
              i,
              preparedLayout.getVerticalOffset(),
              metrics);
      if (metrics.wasFound) {
        retList.add(PixelUtil.toDIPFromPixel(metrics.top));
        retList.add(PixelUtil.toDIPFromPixel(metrics.left));
        retList.add(PixelUtil.toDIPFromPixel(metrics.width));
        retList.add(PixelUtil.toDIPFromPixel(metrics.height));
      }
    }

    float[] ret = new float[retList.size()];
    for (int i = 0; i < retList.size(); i++) {
      ret[i] = retList.get(i);
    }
    return ret;
  }

  private static float getVerticalOffset(
      Layout layout,
      ReadableMapBuffer paragraphAttributes,
      float height,
      YogaMeasureMode heightMeasureMode,
      int maximumNumberOfLines) {
    @Nullable
    String textAlignVertical =
        paragraphAttributes.contains(TextLayoutManager.PA_KEY_TEXT_ALIGN_VERTICAL)
            ? paragraphAttributes.getString(TextLayoutManager.PA_KEY_TEXT_ALIGN_VERTICAL)
            : null;

    if (textAlignVertical == null) {
      return 0;
    }

    int textHeight = layout.getHeight();
    int calculatedLineCount = calculateLineCount(layout, maximumNumberOfLines);
    float boxHeight = calculateHeight(layout, height, heightMeasureMode, calculatedLineCount);

    if (textHeight > boxHeight) {
      return 0;
    }

    switch (textAlignVertical) {
      case "auto":
      case "top":
        return 0;
      case "center":
        return (boxHeight - textHeight) / 2f;
      case "bottom":
        return boxHeight - textHeight;
      default:
        FLog.w(ReactConstants.TAG, "Invalid textAlignVertical: " + textAlignVertical);
        return 0;
    }
  }

  private static int calculateLineCount(Layout layout, int maximumNumberOfLines) {
    return maximumNumberOfLines == ReactConstants.UNSET || maximumNumberOfLines == 0
        ? layout.getLineCount()
        : Math.min(maximumNumberOfLines, layout.getLineCount());
  }

  private static float calculateWidth(
      Layout layout,
      Spanned text,
      float width,
      YogaMeasureMode widthYogaMeasureMode,
      int calculatedLineCount) {
    // Our layout must be created at a physical pixel boundary, so may be sized smaller by a
    // subpixel compared to the assigned layout width.
    if (widthYogaMeasureMode == YogaMeasureMode.EXACTLY) {
      return width;
    }

    return layout.getWidth();
  }

  private static float calculateHeight(
      Layout layout, float height, YogaMeasureMode heightYogaMeasureMode, int calculatedLineCount) {
    float calculatedHeight = height;
    if (heightYogaMeasureMode != YogaMeasureMode.EXACTLY) {
      // StaticLayout only seems to change its height in response to maxLines when ellipsizing, so
      // we must truncate
      calculatedHeight = layout.getLineBottom(calculatedLineCount - 1);
      if (heightYogaMeasureMode == YogaMeasureMode.AT_MOST && calculatedHeight > height) {
        calculatedHeight = height;
      }
    }
    return calculatedHeight;
  }

  private static class AttachmentMetrics {
    boolean wasFound;
    float top;
    float left;
    float width;
    float height;
  }

  private static int nextAttachmentMetrics(
      Layout layout,
      Spanned text,
      float calculatedWidth,
      int calculatedLineCount,
      int i,
      float verticalOffset,
      AttachmentMetrics metrics) {
    // Calculate the positions of the attachments (views) that will be rendered inside the
    // Spanned Text. The following logic is only executed when a text contains views inside.
    // This follows a similar logic than used in pre-fabric (see ReactTextView.onLayout method).
    int lastAttachmentFoundInSpan =
        text.nextSpanTransition(i, text.length(), TextInlineViewPlaceholderSpan.class);
    TextInlineViewPlaceholderSpan[] placeholders =
        text.getSpans(i, lastAttachmentFoundInSpan, TextInlineViewPlaceholderSpan.class);

    if (placeholders.length == 0) {
      metrics.wasFound = false;
      return lastAttachmentFoundInSpan;
    }

    Assertions.assertCondition(placeholders.length == 1);
    TextInlineViewPlaceholderSpan placeholder = placeholders[0];

    int start = text.getSpanStart(placeholder);
    int line = layout.getLineForOffset(start);
    boolean isLineTruncated = layout.getEllipsisCount(line) > 0;
    boolean isAttachmentTruncated =
        line > calculatedLineCount
            || (isLineTruncated
                && start >= layout.getLineStart(line) + layout.getEllipsisStart(line));
    if (isAttachmentTruncated) {
      metrics.top = Float.NaN;
      metrics.left = Float.NaN;
    } else {
      float placeholderWidth = placeholder.getWidth();
      float placeholderHeight = placeholder.getHeight();
      // Calculate if the direction of the placeholder character is Right-To-Left.
      boolean isRtlChar = layout.isRtlCharAt(start);
      boolean isRtlParagraph = layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
      float placeholderLeftPosition;
      // There's a bug on Samsung devices where calling getPrimaryHorizontal on
      // the last offset in the layout will result in an endless loop. Work around
      // this bug by avoiding getPrimaryHorizontal in that case.
      if (start == text.length() - 1) {
        boolean endsWithNewLine =
            text.length() > 0 && text.charAt(layout.getLineEnd(line) - 1) == '\n';
        float lineWidth = endsWithNewLine ? layout.getLineMax(line) : layout.getLineWidth(line);
        placeholderLeftPosition =
            isRtlParagraph
                // Equivalent to `layout.getLineLeft(line)` but `getLineLeft` returns
                // incorrect
                // values when the paragraph is RTL and `setSingleLine(true)`.
                ? calculatedWidth - lineWidth
                : layout.getLineRight(line) - placeholderWidth;
      } else {
        // The direction of the paragraph may not be exactly the direction the string is
        // heading
        // in at the
        // position of the placeholder. So, if the direction of the character is the same
        // as the
        // paragraph
        // use primary, secondary otherwise.
        boolean characterAndParagraphDirectionMatch = isRtlParagraph == isRtlChar;
        placeholderLeftPosition =
            characterAndParagraphDirectionMatch
                ? layout.getPrimaryHorizontal(start)
                : layout.getSecondaryHorizontal(start);
        if (isRtlParagraph && !isRtlChar) {
          // Adjust `placeholderLeftPosition` to work around an Android bug.
          // The bug is when the paragraph is RTL and `setSingleLine(true)`, some layout
          // methods such as `getPrimaryHorizontal`, `getSecondaryHorizontal`, and
          // `getLineRight` return incorrect values. Their return values seem to be off
          // by the same number of pixels so subtracting these values cancels out the
          // error.
          //
          // The result is equivalent to bugless versions of
          // `getPrimaryHorizontal`/`getSecondaryHorizontal`.
          placeholderLeftPosition =
              calculatedWidth - (layout.getLineRight(line) - placeholderLeftPosition);
        }
        if (isRtlChar) {
          placeholderLeftPosition -= placeholderWidth;
        }
      }
      // Vertically align the inline view to the baseline of the line of text.
      float placeholderTopPosition = layout.getLineBaseline(line) - placeholderHeight;

      // The attachment array returns the positions of each of the attachments as
      metrics.top = placeholderTopPosition;
      metrics.left = placeholderLeftPosition;
    }

    // The text may be vertically aligned to the top, center, or bottom of the container. This is
    // not captured in the Layout, but rather applied separately. We need to account for this here.
    metrics.top += verticalOffset;

    metrics.wasFound = true;
    metrics.width = placeholder.getWidth();
    metrics.height = placeholder.getHeight();
    return lastAttachmentFoundInSpan;
  }

  public static WritableArray measureLines(
      Context context,
      MapBuffer attributedString,
      MapBuffer paragraphAttributes,
      float width,
      float height) {
    Layout layout =
        createLayoutForMeasurement(
            context,
            attributedString,
            paragraphAttributes,
            width,
            YogaMeasureMode.EXACTLY,
            height,
            YogaMeasureMode.EXACTLY,
            // TODO T226571550: Fix measureLines with ReactTextViewManagerCallback
            null);
    return FontMetricsUtil.getFontMetrics(layout.getText(), layout, context);
  }

  private static @Nullable BoringLayout.Metrics isBoring(Spannable text, TextPaint paint) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return BoringLayout.isBoring(text, paint);
    } else {
      // Default to include fallback line spacing on Android 13+, like TextView
      // https://cs.android.com/android/_/android/platform/frameworks/base/+/78c774defb238c05c42b34a12b6b3b0c64844ed7
      return BoringLayout.isBoring(
          text, paint, TextDirectionHeuristics.FIRSTSTRONG_LTR, true, null);
    }
  }
}
