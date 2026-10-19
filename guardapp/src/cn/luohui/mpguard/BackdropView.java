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
    private float density;
    private Shader limeGlow;
    private Shader warmGlow;

    public BackdropView(Context context) {
        this(context, null);
    }

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        grid.setColor(0x0BFFFFFF);
        grid.setStrokeWidth(density * 0.7f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        limeGlow = new RadialGradient(
                w * 0.88f, -h * 0.02f, w * 0.95f,
                new int[]{0x2E3E5C2F, 0x001B2A1C},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        warmGlow = new RadialGradient(
                w * 0.02f, h * 0.62f, w * 0.7f,
                new int[]{0x10FFC46B, 0x00FFC46B},
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
