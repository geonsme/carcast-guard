package cn.luohui.mpguard;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * 磨砂玻璃卡片：从全屏背景的离屏模糊图中裁出自身区域，叠加色调、顶部高光与描边。
 * 模糊图由 StatusActivity 布局完成后生成一次，滚动时按窗口位置实时取样。
 */
public class GlassCardView extends FrameLayout {

    private static final float DEFAULT_RADIUS_DP = 24f;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private final int[] location = new int[2];

    private final int tintColor;
    private final int sheenColor;
    private final int strokeTop;
    private final int strokeBottom;

    private Bitmap backdrop;
    private float backdropScale = 0.25f;
    private int backdropX;
    private int backdropY;
    private BitmapShader bitmapShader;
    private LinearGradient sheenShader;
    private LinearGradient strokeShader;
    private float radius;

    public GlassCardView(Context context) {
        this(context, null);
    }

    public GlassCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        radius = DEFAULT_RADIUS_DP * density;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassCardView);
            radius = a.getDimension(R.styleable.GlassCardView_glassCornerRadius, radius);
            a.recycle();
        }

        tintColor = context.getColor(R.color.glass_tint);
        sheenColor = context.getColor(R.color.glass_sheen);
        strokeTop = context.getColor(R.color.glass_stroke_top);
        strokeBottom = context.getColor(R.color.glass_stroke_bottom);

        tintPaint.setColor(tintColor);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, density));

        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.12f);
        bitmapPaint.setColorFilter(new ColorMatrixColorFilter(saturation));
        setWillNotDraw(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
    }

    public void setBackdrop(Bitmap bitmap, float scale, int originX, int originY) {
        backdrop = bitmap;
        backdropScale = scale <= 0 ? 0.25f : scale;
        backdropX = originX;
        backdropY = originY;
        bitmapShader = null;
        if (backdrop == null) bitmapPaint.setShader(null);
        invalidate();
    }

    public void setCornerRadius(float px) {
        radius = px;
        invalidateOutline();
        invalidate();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bounds.set(0, 0, w, h);
        if (h > 0) {
            sheenShader = new LinearGradient(0, 0, 0, h * 0.82f,
                    sheenColor, withAlpha(sheenColor, 0), Shader.TileMode.CLAMP);
            strokeShader = new LinearGradient(0, 0, 0, h,
                    strokeTop, strokeBottom, Shader.TileMode.CLAMP);
        }
        invalidateOutline();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bounds.isEmpty()) return;

        if (backdrop != null && !backdrop.isRecycled()) {
            if (bitmapShader == null) {
                bitmapShader = new BitmapShader(backdrop, Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP);
                bitmapPaint.setShader(bitmapShader);
            }
            getLocationInWindow(location);
            float inv = 1f / backdropScale;
            shaderMatrix.setScale(inv, inv);
            shaderMatrix.postTranslate(-(location[0] - backdropX), -(location[1] - backdropY));
            bitmapShader.setLocalMatrix(shaderMatrix);
            canvas.drawRoundRect(bounds, radius, radius, bitmapPaint);
        }

        canvas.drawRoundRect(bounds, radius, radius, tintPaint);

        if (sheenShader != null) {
            sheenPaint.setShader(sheenShader);
            canvas.drawRoundRect(bounds, radius, radius, sheenPaint);
        }
        if (strokeShader != null) {
            strokePaint.setShader(strokeShader);
            canvas.drawRoundRect(bounds, radius, radius, strokePaint);
        }
    }

    // ---------- 背景模糊 ----------

    /** 把背景 View 缩小绘制后做三次盒式模糊，得到柔和的磨砂底图。 */
    public static Bitmap blurBackdrop(View source, float scale, int radius) {
        int w = Math.max(1, Math.round(source.getWidth() * scale));
        int h = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        source.draw(canvas);
        boxBlur(bitmap, radius, 3);
        return bitmap;
    }

    private static void boxBlur(Bitmap bitmap, int radius, int passes) {
        if (radius <= 0 || passes <= 0) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] src = new int[w * h];
        bitmap.getPixels(src, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int i = 0; i < passes; i++) {
            blurLine(src, tmp, w, h, radius, true);
            blurLine(tmp, src, w, h, radius, false);
        }
        bitmap.setPixels(src, 0, w, 0, 0, w, h);
    }

    private static void blurLine(int[] src, int[] dst, int w, int h,
                                 int radius, boolean horizontal) {
        int length = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int div = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int r = 0;
            int g = 0;
            int b = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = src[indexOf(line, clamp(i, length), w, horizontal)];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int pos = 0; pos < length; pos++) {
                dst[indexOf(line, pos, w, horizontal)] =
                        (0xFF << 24) | ((r / div) << 16) | ((g / div) << 8) | (b / div);
                int add = src[indexOf(line, clamp(pos + radius + 1, length), w, horizontal)];
                int sub = src[indexOf(line, clamp(pos - radius, length), w, horizontal)];
                r += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                b += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int indexOf(int line, int pos, int w, boolean horizontal) {
        return horizontal ? line * w + pos : pos * w + line;
    }

    private static int clamp(int value, int max) {
        if (value < 0) return 0;
        if (value >= max) return max - 1;
        return value;
    }
}
