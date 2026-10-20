package cn.luohui.mpguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/** 氛围背景：大尺度绿/暖/冷三色光晕 + 极细颗粒，作为磨砂玻璃的取景底图。 */
public class BackdropView extends View {

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int base;
    private final int greenInner;
    private final int greenOuter;
    private final int warmInner;
    private final int warmOuter;
    private final int coolInner;
    private final int coolOuter;
    private Shader greenGlow;
    private Shader warmGlow;
    private Shader coolGlow;
    private static Bitmap noise;

    public BackdropView(Context context) {
        this(context, null);
    }

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        base = context.getColor(R.color.backdrop_base);
        greenInner = context.getColor(R.color.backdrop_glow_a_inner);
        greenOuter = context.getColor(R.color.backdrop_glow_a_outer);
        warmInner = context.getColor(R.color.backdrop_glow_b_inner);
        warmOuter = context.getColor(R.color.backdrop_glow_b_outer);
        coolInner = context.getColor(R.color.backdrop_glow_c_inner);
        coolOuter = context.getColor(R.color.backdrop_glow_c_outer);
        grain.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT));
    }

    private static synchronized Bitmap noise() {
        if (noise != null) return noise;
        int size = 128;
        int[] pixels = new int[size * size];
        Random random = new Random(20261020L);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = random.nextInt(9);
            int tone = random.nextBoolean() ? 0xFFFFFF : 0x000000;
            pixels[i] = (alpha << 24) | tone;
        }
        noise = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        noise.setPixels(pixels, 0, size, 0, 0, size, size);
        return noise;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        greenGlow = new RadialGradient(
                w * 0.98f, -h * 0.03f, w * 1.02f,
                new int[]{greenInner, greenOuter},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        warmGlow = new RadialGradient(
                -w * 0.08f, h * 0.52f, w * 0.86f,
                new int[]{warmInner, warmOuter},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        coolGlow = new RadialGradient(
                w * 0.86f, h * 1.04f, w * 0.82f,
                new int[]{coolInner, coolOuter},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(base);
        glow.setShader(greenGlow);
        canvas.drawPaint(glow);
        glow.setShader(warmGlow);
        canvas.drawPaint(glow);
        glow.setShader(coolGlow);
        canvas.drawPaint(glow);
        canvas.drawPaint(grain);
    }
}
