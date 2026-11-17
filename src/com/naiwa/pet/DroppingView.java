package com.naiwa.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 独立排泄物实体：便便 / 尿渍 / 白色液体，作为单独悬浮窗留在地面，可被任意p1吃掉。
 */
public class DroppingView extends View {

    public static final int TYPE_POOP = 0, TYPE_PEE = 1, TYPE_WHITE = 2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int type;

    public DroppingView(Context context, int type) {
        super(context);
        this.type = type;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        if (type == TYPE_PEE) {
            paint.setColor(0x66FFD23F);
            oval.set(cx - w * 0.45f, cy - h * 0.30f, cx + w * 0.45f, cy + h * 0.30f);
            c.drawOval(oval, paint);
            paint.setColor(0x33FFD23F);
            oval.set(cx - w * 0.26f, cy - h * 0.40f, cx + w * 0.26f, cy + h * 0.40f);
            c.drawOval(oval, paint);
        } else if (type == TYPE_WHITE) {
            paint.setColor(0xCCF4F4F0);
            oval.set(cx - w * 0.40f, cy - h * 0.25f, cx + w * 0.40f, cy + h * 0.25f);
            c.drawOval(oval, paint);
            paint.setColor(0x88FFFFFF);
            oval.set(cx - w * 0.20f, cy - h * 0.35f, cx + w * 0.20f, cy + h * 0.35f);
            c.drawOval(oval, paint);
            paint.setColor(0xBBF4F4F0);
            oval.set(cx - w * 0.10f, cy - h * 0.40f, cx + w * 0.10f, cy + h * 0.40f);
            c.drawOval(oval, paint);
        } else {
            float s = Math.min(w, h) * 0.5f;
            paint.setColor(0xFF8B5A2B);
            oval.set(cx - s * 0.6f, cy - s * 0.42f, cx + s * 0.6f, cy + s * 0.10f);
            c.drawOval(oval, paint);
            oval.set(cx - s * 0.42f, cy - s * 0.68f, cx + s * 0.42f, cy - s * 0.16f);
            c.drawOval(oval, paint);
            oval.set(cx - s * 0.24f, cy - s * 0.86f, cx + s * 0.24f, cy - s * 0.44f);
            c.drawOval(oval, paint);
            paint.setColor(0x33000000);
            c.drawCircle(cx - s * 0.2f, cy - s * 0.5f, s * 0.05f, paint);
        }
    }
}
