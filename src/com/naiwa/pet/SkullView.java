package com.naiwa.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 奶蛙死后的骷髅头：点击/长按可清理或复活。
 */
public class SkullView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final boolean ashes;

    public SkullView(Context context, boolean ashes) {
        super(context);
        this.ashes = ashes;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cy = h * 0.50f;
        float r = Math.min(w, h) * 0.32f;

        // 地面阴影
        paint.setColor(0x22000000);
        oval.set(cx - r * 1.1f, h * 0.86f, cx + r * 1.1f, h * 0.96f);
        c.drawOval(oval, paint);

        // 头骨
        paint.setColor(ashes ? 0xFFE3E3E3 : 0xFFF2EFE6);
        c.drawCircle(cx, cy, r, paint);
        // 下巴
        paint.setColor(ashes ? 0xFFD6D6D6 : 0xFFE7E1D1);
        oval.set(cx - r * 0.55f, cy + r * 0.35f, cx + r * 0.55f, cy + r * 1.0f);
        c.drawOval(oval, paint);

        // 眼窝
        paint.setColor(0xCC262626);
        c.drawCircle(cx - r * 0.40f, cy - r * 0.08f, r * 0.22f, paint);
        c.drawCircle(cx + r * 0.40f, cy - r * 0.08f, r * 0.22f, paint);

        // 鼻孔
        paint.setColor(0xCC3A3A3A);
        oval.set(cx - r * 0.06f, cy + r * 0.24f, cx + r * 0.06f, cy + r * 0.36f);
        c.drawOval(oval, paint);

        // 牙
        paint.setColor(0xFFBDB6A4);
        for (int i = -1; i <= 1; i++) {
            oval.set(cx + i * r * 0.18f - r * 0.06f, cy + r * 0.52f,
                    cx + i * r * 0.18f + r * 0.06f, cy + r * 0.82f);
            c.drawOval(oval, paint);
        }
        // 头骨高光
        paint.setColor(0x33FFFFFF);
        c.drawCircle(cx - r * 0.45f, cy - r * 0.45f, r * 0.18f, paint);
    }
}
