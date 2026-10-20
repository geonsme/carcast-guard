package cn.luohui.mpguard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 守护指数圆环：360° 细环 + 圆头进度 + 分数，克制、无刻度。 */
public class ProtectionGaugeView extends View {

    private static final float START_ANGLE = -90f;
    private static final float SWEEP_ANGLE = 360f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;
    private float cx;
    private float cy;
    private float radius;
    private float shown;
    private int target;
    private int accent;
    private Shader arcShader;
    private Shader coreGlow;
    private ValueAnimator animator;

    public ProtectionGaugeView(Context context) {
        this(context, null);
    }

    public ProtectionGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        accent = context.getColor(R.color.accent);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(8f));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(context.getColor(R.color.gauge_track));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(8f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(accent);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(18f));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(withAlpha(accent, 26));

        scorePaint.setTextAlign(Paint.Align.CENTER);
        scorePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        scorePaint.setColor(context.getColor(R.color.text_primary));

        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setLetterSpacing(0.12f);
        labelPaint.setColor(withAlpha(accent, 150));

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(accent);
    }

    private float dp(float v) { return v * density; }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int blend(int color, int other, float ratio) {
        int r = (int) (Color.red(color) * (1 - ratio) + Color.red(other) * ratio);
        int g = (int) (Color.green(color) * (1 - ratio) + Color.green(other) * ratio);
        int b = (int) (Color.blue(color) * (1 - ratio) + Color.blue(other) * ratio);
        return Color.rgb(r, g, b);
    }

    /** 更新分数；颜色传当前守护等级色。 */
    public void setScore(int score, int color, boolean animate) {
        score = Math.max(0, Math.min(100, score));
        accent = color;
        labelPaint.setColor(withAlpha(accent, 150));
        dotPaint.setColor(accent);
        buildShaders();
        if (target == score) {
            invalidate();
            return;
        }
        target = score;
        if (animator != null) animator.cancel();
        if (!animate) {
            shown = score;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(shown, score);
        animator.setDuration(720);
        animator.setInterpolator(new DecelerateInterpolator(1.7f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - dp(14f);
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        scorePaint.setTextSize(dp(44f));
        labelPaint.setTextSize(dp(9.5f));
        buildShaders();
    }

    private void buildShaders() {
        if (radius <= 0) return;
        arcShader = new LinearGradient(
                cx - radius, cy - radius, cx + radius, cy + radius,
                blend(accent, Color.WHITE, 0.2f), accent, Shader.TileMode.CLAMP);
        coreGlow = new RadialGradient(
                cx, cy, radius * 0.86f,
                new int[]{withAlpha(accent, 16), withAlpha(accent, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        glowPaint.setColor(withAlpha(accent, 26));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;

        corePaint.setShader(coreGlow);
        canvas.drawCircle(cx, cy, radius * 0.86f, corePaint);
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint);

        float sweep = SWEEP_ANGLE * shown / 100f;
        if (sweep > 0.2f) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, glowPaint);
            arcPaint.setShader(arcShader);
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, arcPaint);
        }

        if (shown > 0.5f) {
            double angle = Math.toRadians(START_ANGLE + sweep);
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            dotPaint.setColor(withAlpha(accent, 48));
            canvas.drawCircle(px, py, dp(8f), dotPaint);
            dotPaint.setColor(accent);
            canvas.drawCircle(px, py, dp(3.4f), dotPaint);
        }

        Paint.FontMetrics fm = scorePaint.getFontMetrics();
        float scoreCenterY = cy - dp(4f);
        float baseline = scoreCenterY - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(String.valueOf(Math.round(shown)), cx, baseline, scorePaint);
        canvas.drawText("/ 100", cx, cy + dp(28f), labelPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
