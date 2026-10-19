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

/** 守护指数仪表盘：270° 刻度环 + 实时分数 + 进度游标。 */
public class ProtectionGaugeView extends View {

    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;
    private static final int TICKS = 28;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private final Typeface scoreType;
    private final Typeface labelType;

    private float density;
    private float cx;
    private float cy;
    private float radius;
    private float shown;
    private int target;
    private int accent = 0xFFC7F86F;
    private Shader arcShader;
    private Shader coreGlow;
    private ValueAnimator animator;

    public ProtectionGaugeView(Context context) {
        this(context, null);
    }

    public ProtectionGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        scoreType = loadFont(R.font.rajdhani_semibold, Typeface.create("sans-serif", Typeface.NORMAL));
        labelType = loadFont(R.font.rajdhani_medium, Typeface.create("sans-serif", Typeface.NORMAL));

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(8.5f));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0xFF1C2521);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(8.5f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(accent);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(19f));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(withAlpha(accent, 30));

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(dp(1.1f));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        scorePaint.setTextAlign(Paint.Align.CENTER);
        scorePaint.setTypeface(scoreType);
        scorePaint.setColor(0xFFF2F6F0);

        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(labelType);
        labelPaint.setLetterSpacing(0.16f);
        labelPaint.setColor(withAlpha(accent, 170));

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(accent);
    }

    private Typeface loadFont(int id, Typeface fallback) {
        try {
            return getResources().getFont(id);
        } catch (Exception e) {
            return fallback;
        }
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
        labelPaint.setColor(withAlpha(accent, 170));
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
        animator.setDuration(680);
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
        radius = Math.min(w, h) / 2f - dp(26f);
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        scorePaint.setTextSize(dp(58f));
        labelPaint.setTextSize(dp(8.5f));
        buildShaders();
    }

    private void buildShaders() {
        if (radius <= 0) return;
        arcShader = new LinearGradient(
                cx - radius, cy - radius, cx + radius, cy + radius,
                blend(accent, Color.WHITE, 0.28f), accent, Shader.TileMode.CLAMP);
        coreGlow = new RadialGradient(
                cx, cy, radius * 0.72f,
                new int[]{withAlpha(accent, 24), withAlpha(accent, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        glowPaint.setColor(withAlpha(accent, 30));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(coreGlow);
        canvas.drawCircle(cx, cy, radius * 0.72f, p);

        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint);

        float sweep = SWEEP_ANGLE * shown / 100f;
        canvas.drawArc(arcRect, START_ANGLE, sweep, false, glowPaint);
        arcPaint.setShader(arcShader);
        canvas.drawArc(arcRect, START_ANGLE, sweep, false, arcPaint);

        for (int i = 0; i <= TICKS; i++) {
            double angle = Math.toRadians(START_ANGLE + SWEEP_ANGLE * i / TICKS);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float inner = radius + dp(12f);
            float outer = radius + dp(17f);
            boolean lit = (i * 100f / TICKS) <= shown;
            tickPaint.setColor(lit ? withAlpha(accent, 155) : 0xFF2C3631);
            canvas.drawLine(cx + cos * inner, cy + sin * inner,
                    cx + cos * outer, cy + sin * outer, tickPaint);
        }

        if (shown > 0.5f) {
            double angle = Math.toRadians(START_ANGLE + sweep);
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            dotPaint.setColor(withAlpha(accent, 45));
            canvas.drawCircle(px, py, dp(8f), dotPaint);
            dotPaint.setColor(accent);
            canvas.drawCircle(px, py, dp(3.2f), dotPaint);
        }

        Paint.FontMetrics fm = scorePaint.getFontMetrics();
        float scoreBaseline = cy - (fm.ascent + fm.descent) / 2f + dp(1f);
        canvas.drawText(String.valueOf(Math.round(shown)), cx, scoreBaseline, scorePaint);

        labelPaint.setColor(withAlpha(accent, 120));
        canvas.drawText("GUARD INDEX", cx, cy - dp(38f), labelPaint);
        labelPaint.setColor(withAlpha(accent, 185));
        canvas.drawText("SCORE / 100", cx, cy + dp(36f), labelPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
