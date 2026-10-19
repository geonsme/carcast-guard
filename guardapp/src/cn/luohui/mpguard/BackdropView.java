package cn.luohui.mpguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 深色底纹：冷绿光晕 + 极淡的仪表网格。 */
public class BackdropView extends View {

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int limeInner;
    private final int limeOuter;
    private final int warmInner;
    private final int warmOuter;
    private float density;
    private Shader limeGlow;
    private Shader warmGlow;

    public BackdropView(Context context) {
        this(context, null);
    }

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        grid.setColor(context.getColor(R.color.backdrop_grid));
        grid.setStrokeWidth(density * 0.7f);
        limeInner = context.getColor(R.color.backdrop_lime_inner);
        limeOuter = context.getColor(R.color.backdrop_lime_outer);
        warmInner = context.getColor(R.color.backdrop_warm_inner);
        warmOuter = context.getColor(R.color.backdrop_warm_outer);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        limeGlow = new RadialGradient(
                w * 0.88f, -h * 0.02f, w * 0.95f,
                new int[]{limeInner, limeOuter},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        warmGlow = new RadialGradient(
                w * 0.02f, h * 0.62f, w * 0.7f,
                new int[]{warmInner, warmOuter},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        glow.setShader(limeGlow);
        canvas.drawPaint(glow);
        glow.setShader(warmGlow);
        canvas.drawPaint(glow);

        float step = density * 26f;
        for (float x = step; x < getWidth(); x += step) {
            canvas.drawLine(x, 0, x, getHeight(), grid);
        }
        for (float y = step; y < getHeight(); y += step) {
            canvas.drawLine(0, y, getWidth(), y, grid);
        }
    }
}
