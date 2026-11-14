package com.naiwa.pet;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.io.InputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 奶蛙本体：绘制、呼吸/眨眼/眼神等基础动画，以及点击、跳舞、哭泣、死亡、复活、
 * 吃饭、拉屎、尿尿、吃排泄物、被吃等行为动画。
 */
public class PetView extends View {

    public interface Listener {
        void playSound(int type);
        void onBeingEatenFinished(PetView who);
        void onDied(PetView who);
        void onDrop(PetView who, int type);
        void onThunderDied(PetView who);
        void onAgeDied(PetView who);
    }

    public static final int S_CLICK = 1, S_HAPPY = 2, S_SAD = 3, S_DEATH = 4, S_REVIVE = 5,
            S_DANCE = 6, S_POOP = 7, S_PEE = 8, S_EAT = 9;
    public static final int D_POOP = 0, D_PEE = 1, D_WHITE = 2;

    private enum Mode { NORMAL, DANCE, CRY, DYING, DEAD, REVIVING, EATING, POOPING, PEEING, GULPING, SITTING, MATING, SOLO, AGE_DYING, LAUGH }

    private Listener listener;
    private Mode mode = Mode.NORMAL;
    private float saturation = 1f;
    private float rotation = 0f;
    private float offsetY = 0f;
    private float dancePhase = 0f;
    private float cryPhase = 0f;
    private float shakeX = 0f;
    private float scale = 1f;
    private float hoverScale = 1f, targetHoverScale = 1f;
    private float hoverTilt = 0f, targetHoverTilt = 0f;

    // 基础生命感
    private float breathPhase = 0f, breath = 0f;
    private float blink = 1f, blinkT = -1f;
    private long nextBlinkAt = 0, nextPupilAt = 0;
    private float pupilX = 0f, pupilY = 0f, pupilTx = 0f, pupilTy = 0f;

    // 吃/拉/尿
    private float mouthOpen = 0f;
    private float foodAlpha = 0f, foodPos = 0f, foodScale = 1f;
    private float eatT = 0f;
    private int hunger = 0;
    private float peeT = 0f;
    private boolean peeActive = false;
    private float thunderT = -1f;
    private int eatDroppingType = -1;
    private boolean rolledEyes = false;
    private boolean running = false;
    private int runDir = 1;
    private float runPhase = 0f;
    private long nextSmokeAt = 0;

    private ValueAnimator current;
    private final Random rnd = new Random();
    private final Handler ticker = new Handler(Looper.getMainLooper());

    private static class Particle {
        float x, y, vx, vy, g, life, size;
        int kind; // 0心 1星 2音符 3魂
        float alpha;
    }
    private final List<Particle> particles = new ArrayList<>();

    private final float density;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final ColorMatrix cm = new ColorMatrix();
    private Bitmap bmpIdle;
    private Bitmap[] bmpLook, bmpBlink, bmpLaugh;
    private int laughFrame = 0;
    private long lastLaughFrameAt = 0;

    private static final int C_BODY = 0xFFFFD84D;
    private static final int C_BODY_DARK = 0xFFE3B100;
    private static final int C_BODY_LIGHT = 0xFFFFF2A8;
    private static final int C_BELLY = 0xFFFFF8D2;
    private static final int C_EYE_WHITE = 0xFFFFFFFF;
    private static final int C_IRIS = 0xFF5BE03C;
    private static final int C_IRIS_DARK = 0xFF2A9E1B;
    private static final int C_PUPIL = 0xFF123B12;
    private static final int C_BLUSH = 0x55FF8FA3;
    private static final int C_MOUTH = 0xFF6D4C00;
    private static final int C_TEAR = 0xBB7EC8FF;
    private static final int C_XEYE = 0xFF5B3A00;
    private static final int C_HEART = 0xE6FF5C7A;
    private static final int C_POOP = 0xFF8B5A2B;
    private static final int C_PEE = 0x66FFD23F;
    private static final int C_FOOD = 0xFFC98A4B;
    private static final int C_BONE = 0xFFFFF4E0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            breathPhase += 0.09f;
            breath = (float) Math.sin(breathPhase);

            if (now > nextPupilAt) {
                pupilTx = (rnd.nextFloat() - 0.5f) * 2.6f;
                pupilTy = (rnd.nextFloat() - 0.5f) * 2.0f;
                nextPupilAt = now + 1700 + rnd.nextInt(2600);
            }
            pupilX += (pupilTx - pupilX) * 0.10f;
            pupilY += (pupilTy - pupilY) * 0.10f;

            if (blinkT < 0) {
                blink = 1f;
                if (now > nextBlinkAt) {
                    blinkT = 0f;
                    nextBlinkAt = now + 2200 + rnd.nextInt(4200);
                }
            } else {
                blinkT += 0.16f;
                if (blinkT >= 1f) {
                    blinkT = -1f;
                    blink = 1f;
                } else {
                    blink = 1f - (float) Math.sin(Math.PI * blinkT);
                    if (blink < 0f) blink = 0f;
                }
            }

            hoverScale += (targetHoverScale - hoverScale) * 0.16f;
            hoverTilt += (targetHoverTilt - hoverTilt) * 0.16f;

            if (running) {
                runPhase += 0.30f;
                rotation = runDir * 7f * (float) Math.sin(runPhase);
                offsetY = -(float) Math.abs(Math.sin(runPhase * 2)) * dp(3);
                if (now > nextSmokeAt) {
                    spawnSmoke();
                    nextSmokeAt = now + 110;
                }
            }

            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle p = particles.get(i);
                p.x += p.vx;
                p.y += p.vy;
                p.vy += p.g;
                p.life -= 0.018f;
                if (p.life <= 0f) particles.remove(i);
            }

            invalidate();
            ticker.postDelayed(this, 55);
        }
    };

    public PetView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        bmpIdle = loadAsset("idle.png");
        bmpLook = loadFrames("look_", 11);
        bmpBlink = loadFrames("blink_", 7);
        bmpLaugh = loadFrames("laugh_", 16);
    }

    private Bitmap loadAsset(String name) {
        try {
            InputStream is = getContext().getAssets().open("naiwa/" + name);
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap[] loadFrames(String prefix, int n) {
        Bitmap[] arr = new Bitmap[n];
        for (int i = 0; i < n; i++) {
            arr[i] = loadAsset(prefix + String.format(java.util.Locale.US, "%02d", i) + ".png");
        }
        return arr;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        nextBlinkAt = SystemClock.uptimeMillis() + 1200 + rnd.nextInt(2000);
        ticker.postDelayed(tick, 60);
    }

    @Override
    protected void onDetachedFromWindow() {
        ticker.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isDead() {
        return mode == Mode.DEAD || mode == Mode.DYING || mode == Mode.GULPING || mode == Mode.AGE_DYING;
    }

    public boolean isBusy() {
        return running || (mode != Mode.NORMAL && mode != Mode.DEAD);
    }

    private float dp(float v) {
        return v * density;
    }

    private void play(int type) {
        if (listener != null) listener.playSound(type);
    }

    private void cancelCurrent() {
        if (current != null) {
            ValueAnimator a = current;
            current = null;
            a.cancel();
        }
    }

    public void setHovering(boolean hovering) {
        targetHoverScale = hovering ? 1.06f : 1f;
        targetHoverTilt = hovering ? 4f : 0f;
    }

    public void startRunning(int dir) {
        cancelCurrent();
        runDir = dir >= 0 ? 1 : -1;
        running = true;
        runPhase = 0f;
        nextSmokeAt = 0;
        rotation = 0f;
        offsetY = 0f;
        invalidate();
    }

    public void stopRunning() {
        running = false;
        rotation = 0f;
        offsetY = 0f;
        invalidate();
    }

    // ---------- 点击 ----------
    public void poke() {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(420);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            rotation = (float) Math.sin(t * Math.PI * 5) * 10f * (1f - t);
            scale = 1f + 0.15f * (float) Math.sin(Math.PI * t);
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.NORMAL) { rotation = 0; scale = 1; }
                invalidate();
            }
        });
        current = a;
        a.start();
        play(rnd.nextBoolean() ? S_HAPPY : S_CLICK);
        spawnHearts(3);
    }

    // ---------- 跳舞 ----------
    public void dance() {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        mode = Mode.DANCE;
        play(S_DANCE);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2 * 2.5));
        a.setDuration(3200);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(x -> {
            float p = (float) x.getAnimatedValue();
            dancePhase = p;
            rotation = (float) Math.sin(p) * 14f;
            offsetY = -(float) Math.abs(Math.sin(p * 2)) * dp(10);
            if (rnd.nextInt(20) == 0) spawnNote();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.DANCE) { mode = Mode.NORMAL; rotation = 0; offsetY = 0; }
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 哭泣 ----------
    public void cry() {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        mode = Mode.CRY;
        cryPhase = 0f;
        play(S_SAD);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(3400);
        a.addUpdateListener(x -> {
            cryPhase += 0.075f;
            shakeX = (float) Math.sin(cryPhase * 30) * dp(1.2f);
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.CRY) { mode = Mode.NORMAL; shakeX = 0; }
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 喂食 ----------
    public void feed() {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        mode = Mode.EATING;
        play(S_EAT);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1250);
        a.addUpdateListener(x -> {
            eatT = (float) x.getAnimatedValue();
            float t = eatT;
            if (t < 0.45f) {
                mouthOpen = t / 0.45f;
                foodAlpha = Math.min(1f, t / 0.25f);
                foodPos = 0.6f + 0.4f * (t / 0.45f);
                foodScale = 1f;
            } else {
                float c = (t - 0.45f) / 0.55f;
                mouthOpen = (float) (Math.abs(Math.sin(c * Math.PI * 3)) * (1f - c));
                foodAlpha = Math.max(0f, 1f - c * 1.6f);
                foodScale = Math.max(0.3f, 1f - c * 0.9f);
                if (c > 0.4f) spawnHeartAt();
                if (rnd.nextInt(2) == 0) spawnAtMouth(10, 2);
            }
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                mouthOpen = 0f; foodAlpha = 0f; foodScale = 1f;
                if (mode == Mode.EATING) mode = Mode.NORMAL;
                hunger++;
                // 吃饱了，过一会儿自然要拉屎（符合常识）
                if (hunger >= 1) {
                    ticker.postDelayed(() -> {
                        if (!isDead() && !isBusy()) poop();
                    }, 7000 + rnd.nextInt(8000));
                }
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 拉屎 ----------
    public void poop() {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        mode = Mode.POOPING;
        play(S_POOP);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(950);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            scale = 1f - 0.14f * (float) Math.sin(Math.PI * t);
            offsetY = dp(2) * (float) Math.sin(Math.PI * t);
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.POOPING) {
                    mode = Mode.NORMAL;
                    addDropping(D_POOP);
                    float[] g = groundPoint();
                    spawnSplat(g[0] + (rnd.nextFloat() - 0.5f) * g[2], g[1] - dp(2), 8, 5);
                }
                scale = 1f; offsetY = 0; hunger = 0;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 尿尿 ----------
    public void pee() {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        mode = Mode.PEEING;
        play(S_PEE);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(850);
        peeActive = true;
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            peeT = t;
            rotation = 6f * (float) Math.sin(Math.PI * t);
            offsetY = dp(1.5f) * (float) Math.sin(Math.PI * t);
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                peeActive = false;
                if (mode == Mode.PEEING) {
                    mode = Mode.NORMAL;
                    addDropping(D_PEE);
                    float[] g = groundPoint();
                    for (int i = 0; i < 3; i++) {
                        spawnSplat(g[0] + (rnd.nextFloat() - 0.5f) * g[2] * 1.2f, g[1] + dp(2), 2, 6);
                    }
                }
                rotation = 0; offsetY = 0;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 吃排泄物 ----------
    public void playEatDropping(int type) {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        mode = Mode.EATING;
        eatDroppingType = type;
        play(S_EAT);
        final long dur = type == D_WHITE ? 3000 : 650;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(dur);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            rotation = 24f * (float) Math.sin(Math.PI * t);
            mouthOpen = (float) Math.sin(Math.PI * t);
            if (type == D_WHITE && rnd.nextInt(2) == 0) spawnAtMouth(8, 2);
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.EATING) mode = Mode.NORMAL;
                rotation = 0; mouthOpen = 0;
                eatDroppingType = -1;
                spawnHearts(1);
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 被闪电劈死 ----------
    public void thunderDeath() {
        if (isDead()) return;
        cancelCurrent();
        mode = Mode.DYING;
        thunderT = 0f;
        play(S_DEATH);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1000);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            thunderT = t;
            rotation = -85f * Math.max(0f, (t - 0.45f) / 0.55f);
            saturation = 1f - Math.max(0f, (t - 0.35f) / 0.65f);
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.DYING) {
                    mode = Mode.DEAD; rotation = -85f; saturation = 0f; thunderT = -1f;
                    if (listener != null) listener.onThunderDied(PetView.this);
                }
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 自然衰老死亡（倒下 + 翻白眼） ----------
    public void ageDeath() {
        if (isDead()) return;
        cancelCurrent();
        mode = Mode.AGE_DYING;
        play(S_DEATH);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1200);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            rotation = -85f * t;
            saturation = 1f - t;
            rolledEyes = t > 0.3f;
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.AGE_DYING) {
                    mode = Mode.DEAD; rotation = -85f; saturation = 0f; rolledEyes = true;
                    if (listener != null) listener.onAgeDied(PetView.this);
                }
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 捧腹大笑（原版帧序列） ----------
    public void laugh() {
        if (isDead()) return;
        cancelCurrent();
        mode = Mode.LAUGH;
        laughFrame = 0;
        lastLaughFrameAt = 0;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(2600);
        a.addUpdateListener(x -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastLaughFrameAt > 70 && bmpLaugh.length > 0) {
                lastLaughFrameAt = now;
                laughFrame = (laughFrame + 1) % bmpLaugh.length;
            }
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.LAUGH) mode = Mode.NORMAL;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 孤单自娱（仅一个个体点交配） ----------
    public void soloPlay() {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        mode = Mode.SOLO;
        play(S_DANCE);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(3000);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            rotation = 6f * (float) Math.sin(t * Math.PI * 10);
            offsetY = -(float) Math.abs(Math.sin(t * Math.PI * 8)) * dp(3);
            if (rnd.nextInt(3) == 0) spawnHeartAt();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.SOLO) mode = Mode.NORMAL;
                rotation = 0; offsetY = 0;
                for (int i = 0; i < 3; i++) addDropping(D_WHITE);
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 吃（同类/食物特效） ----------
    public void playEat() {
        if (isDead() && mode != Mode.GULPING) return;
        cancelCurrent();
        mode = Mode.EATING;
        play(S_EAT);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1100);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            mouthOpen = (float) Math.abs(Math.sin(t * Math.PI * 2.5));
            if (t > 0.5f) {
                if (rnd.nextInt(8) == 0) spawnAtMouth(1, 1);
                if (rnd.nextInt(2) == 0) spawnAtMouth(4, 1); // 血液飞溅在进食者嘴部
            }
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                mouthOpen = 0;
                if (mode == Mode.EATING) mode = Mode.NORMAL;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 被吃 ----------
    public void playBeingEaten() {
        cancelCurrent();
        mode = Mode.GULPING;
        cryPhase = 0f;
        play(S_SAD);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(850);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            scale = 1f - 0.7f * t;
            rotation = 70f * t;
            cryPhase += 0.10f;
            if (rnd.nextInt(6) == 0) spawnBlood();
            if (rnd.nextInt(10) == 0) spawnStar();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (listener != null) listener.onBeingEatenFinished(PetView.this);
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 死亡 ----------
    public void kill() {
        if (isDead()) return;
        cancelCurrent();
        mode = Mode.DYING;
        play(S_DEATH);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(950);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            rotation = -85f * t;
            saturation = 1f - t;
            if (rnd.nextInt(14) == 0) spawnSoul();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.DYING) {
                    mode = Mode.DEAD; rotation = -85f; saturation = 0f;
                    if (listener != null) listener.onDied(PetView.this);
                }
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 复活 ----------
    public void revive() {
        if (mode != Mode.DEAD && mode != Mode.DYING) return;
        cancelCurrent();
        mode = Mode.REVIVING;
        play(S_REVIVE);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1000);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            rotation = -85f * (1f - t);
            saturation = Math.min(1f, t * 1.1f);
            scale = 1f + 0.18f * (float) Math.sin(Math.PI * t);
            if (t < 0.8f && rnd.nextInt(8) == 0) spawnStar();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                mode = Mode.NORMAL;
                rotation = 0;
                saturation = 1f;
                scale = 1f;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 坐下 ----------
    public void sit() {
        if (isDead() || isBusy()) return;
        cancelCurrent();
        mode = Mode.SITTING;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(3600);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            float k = (float) Math.sin(Math.PI * t);
            scale = 1f - 0.10f * k;
            offsetY = dp(3) * k;
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.SITTING) mode = Mode.NORMAL;
                scale = 1f; offsetY = 0;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 交配 ----------
    public void mate() {
        if (isDead()) return;
        cancelCurrent();
        mode = Mode.MATING;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(4000);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            rotation = 10f * (float) Math.sin(t * Math.PI * 8);
            offsetY = -(float) Math.abs(Math.sin(t * Math.PI * 6)) * dp(4);
            if (rnd.nextInt(4) == 0) spawnHeartAt();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                if (mode == Mode.MATING) mode = Mode.NORMAL;
                rotation = 0;
                offsetY = 0;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 长大（0.3 倍 → 1 倍） ----------
    public void playGrowUp() {
        cancelCurrent();
        mode = Mode.REVIVING;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(2000);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            scale = 0.3f + 0.7f * t;
            if (rnd.nextInt(2) == 0) spawnGem();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                mode = Mode.NORMAL;
                scale = 1f;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 出生 ----------
    public void playSpawn() {
        cancelCurrent();
        mode = Mode.REVIVING;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(500);
        a.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            if (t < 0.6f) scale = 0.25f + (t / 0.6f) * 0.9f;
            else scale = 1.15f - ((t - 0.6f) / 0.4f) * 0.15f;
            if (rnd.nextInt(8) == 0) spawnStar();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                if (current == a) current = null;
                mode = Mode.NORMAL;
                scale = 1f;
                invalidate();
            }
        });
        current = a;
        a.start();
    }

    // ---------- 生成器 ----------
    private float[] groundPoint() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyFoot = h * 0.80f;
        float r = Math.min(w, h) * 0.30f;
        return new float[]{cx, cyFoot, r};
    }

    private void addDropping(int type) {
        if (listener != null) listener.onDrop(this, type);
    }

    private void spawnSplat(float x, float y, int n, int kind) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.kind = kind;
            p.x = x + (rnd.nextFloat() - 0.5f) * dp(10);
            p.y = y - rnd.nextFloat() * dp(4);
            float ang = (float) (rnd.nextFloat() * Math.PI - Math.PI);
            p.vx = (float) Math.cos(ang) * dp(0.7f) * rnd.nextFloat();
            p.vy = -(float) Math.sin(ang) * dp(0.9f) * rnd.nextFloat() - dp(0.4f);
            p.g = dp(0.14f);
            p.life = 1f;
            p.size = dp(1.6f) + rnd.nextFloat() * dp(1.8f);
            particles.add(p);
        }
    }

    private void spawnBlood() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyBody = h * 0.80f - Math.min(w, h) * 0.30f * 1.12f;
        for (int i = 0; i < 3; i++) {
            Particle p = new Particle();
            p.kind = 4;
            p.x = cx + (rnd.nextFloat() - 0.5f) * w * 0.4f;
            p.y = cyBody + (rnd.nextFloat() - 0.5f) * h * 0.25f;
            p.vx = (rnd.nextFloat() - 0.5f) * dp(1.4f);
            p.vy = -dp(1.2f) - rnd.nextFloat() * dp(1.0f);
            p.g = dp(0.10f);
            p.life = 1f;
            p.size = dp(2.5f) + rnd.nextFloat() * dp(2.2f);
            particles.add(p);
        }
    }

    private void spawnSmoke() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyFoot = h * 0.80f;
        float r = Math.min(w, h) * 0.30f;
        Particle p = new Particle();
        p.kind = 7;
        p.x = cx - runDir * r * 0.9f + (rnd.nextFloat() - 0.5f) * dp(6);
        p.y = cyFoot - dp(2) + (rnd.nextFloat() - 0.5f) * dp(4);
        p.vx = -runDir * dp(0.6f);
        p.vy = -dp(0.3f);
        p.g = 0f;
        p.life = 1f;
        p.size = dp(7) + rnd.nextFloat() * dp(4);
        particles.add(p);
    }

    private void spawnWhiteSplash() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float r = Math.min(w, h) * 0.30f;
        float cyBody = h * 0.80f - r * 1.12f;
        for (int i = 0; i < 2; i++) {
            Particle p = new Particle();
            p.kind = 8;
            p.x = cx + r * 0.30f + (rnd.nextFloat() - 0.5f) * dp(6);
            p.y = cyBody + r * 0.30f;
            p.vx = (rnd.nextFloat() - 0.5f) * dp(1.2f);
            p.vy = -dp(0.8f) - rnd.nextFloat() * dp(0.6f);
            p.g = dp(0.06f);
            p.life = 1f;
            p.size = dp(2.5f) + rnd.nextFloat() * dp(2f);
            particles.add(p);
        }
    }

    private void spawnHearts(int n) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyBody = h * 0.80f - Math.min(w, h) * 0.30f * 1.12f;
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.kind = 0;
            p.x = cx + (rnd.nextFloat() - 0.5f) * w * 0.5f;
            p.y = cyBody + (rnd.nextFloat() - 0.5f) * h * 0.25f;
            p.vx = (rnd.nextFloat() - 0.5f) * dp(0.6f);
            p.vy = -dp(1.6f) - rnd.nextFloat() * dp(0.8f);
            p.g = 0f;
            p.life = 1f;
            p.size = dp(6) + rnd.nextFloat() * dp(3);
            particles.add(p);
        }
    }

    private void spawnHeartAt() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyBody = h * 0.80f - Math.min(w, h) * 0.30f * 1.12f;
        Particle p = new Particle();
        p.kind = 0;
        p.x = cx + (rnd.nextFloat() - 0.5f) * w * 0.3f;
        p.y = cyBody;
        p.vy = -dp(1.2f);
        p.g = 0f;
        p.life = 1f;
        p.size = dp(5) + rnd.nextFloat() * dp(2);
        particles.add(p);
    }

    private void spawnNote() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyBody = h * 0.80f - Math.min(w, h) * 0.30f * 1.12f;
        Particle p = new Particle();
        p.kind = 2;
        p.x = cx + (rnd.nextFloat() - 0.5f) * w * 0.6f;
        p.y = cyBody - dp(2);
        p.vy = -dp(1.2f);
        p.g = 0f;
        p.life = 1f;
        p.size = dp(14) + rnd.nextFloat() * dp(6);
        particles.add(p);
    }

    private void spawnGem() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyBody = h * 0.45f;
        Particle p = new Particle();
        p.kind = 9;
        p.x = cx + (rnd.nextFloat() - 0.5f) * w * 0.7f;
        p.y = cyBody + (rnd.nextFloat() - 0.5f) * h * 0.5f;
        p.vx = (rnd.nextFloat() - 0.5f) * dp(1.2f);
        p.vy = -dp(1.4f) - rnd.nextFloat() * dp(0.6f);
        p.g = 0.02f;
        p.life = 1f;
        p.size = dp(13) + rnd.nextFloat() * dp(8);
        particles.add(p);
    }

    private float[] mouthPoint() {
        int w = getWidth(), h = getHeight();
        Bitmap ref = bmpIdle != null ? bmpIdle : (bmpLook != null && bmpLook.length > 0 ? bmpLook[0] : null);
        if (ref == null) return new float[]{w / 2f, h * 0.55f};
        float scale = Math.min(w / (float) ref.getWidth(), h / (float) ref.getHeight());
        float bw = ref.getWidth() * scale;
        float bh = ref.getHeight() * scale;
        float bx = (w - bw) / 2f;
        float by = (h - bh) / 2f;
        return new float[]{bx + bw * 0.5f, by + bh * 0.25f};
    }

    private void spawnAtMouth(int kind, int n) {
        float[] m = mouthPoint();
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.kind = kind;
            p.x = m[0] + (rnd.nextFloat() - 0.5f) * dp(12);
            p.y = m[1] + (rnd.nextFloat() - 0.5f) * dp(6);
            if (kind == 8) {
                p.vx = (rnd.nextFloat() - 0.5f) * dp(1.2f);
                p.vy = -dp(0.8f) - rnd.nextFloat() * dp(0.6f);
                p.g = dp(0.06f);
                p.size = (dp(2.5f) + rnd.nextFloat() * dp(2f)) * 2.5f;
            } else if (kind == 4) {
                p.vx = (rnd.nextFloat() - 0.5f) * dp(1.8f);
                p.vy = -dp(1.2f) - rnd.nextFloat() * dp(1.0f);
                p.g = dp(0.10f);
                p.size = (dp(3f) + rnd.nextFloat() * dp(2.5f)) * 2.5f;
            } else if (kind == 10) {
                p.vx = (rnd.nextFloat() - 0.5f) * dp(1.0f);
                p.vy = -dp(0.7f) - rnd.nextFloat() * dp(0.5f);
                p.g = dp(0.08f);
                p.size = (dp(2.5f) + rnd.nextFloat() * dp(2f)) * 1.5f;
            } else {
                p.vx = (rnd.nextFloat() - 0.5f) * dp(0.8f);
                p.vy = -dp(1.2f);
                p.g = 0.02f;
                p.size = (dp(12) + rnd.nextFloat() * dp(6)) * 2.5f;
            }
            p.life = 1f;
            particles.add(p);
        }
    }

    private void spawnStar() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyBody = h * 0.80f - Math.min(w, h) * 0.30f * 1.12f;
        Particle p = new Particle();
        p.kind = 1;
        p.x = cx + (rnd.nextFloat() - 0.5f) * w * 0.5f;
        p.y = cyBody + (rnd.nextFloat() - 0.5f) * h * 0.3f;
        p.vx = (rnd.nextFloat() - 0.5f) * dp(0.8f);
        p.vy = -dp(1.4f);
        p.g = 0.02f;
        p.life = 1f;
        p.size = dp(12) + rnd.nextFloat() * dp(6);
        particles.add(p);
    }

    private void spawnSoul() {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cyBody = h * 0.80f - Math.min(w, h) * 0.30f * 1.12f;
        Particle p = new Particle();
        p.kind = 3;
        p.x = cx + (rnd.nextFloat() - 0.5f) * w * 0.3f;
        p.y = cyBody - dp(4);
        p.vx = (rnd.nextFloat() - 0.5f) * dp(0.3f);
        p.vy = -dp(0.8f);
        p.g = -0.01f;
        p.life = 1f;
        p.size = dp(14);
        particles.add(p);
    }

    // ---------- 绘制 ----------
    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float r = Math.min(w, h) * 0.30f;
        float cyFoot = h * 0.80f;
        float cyBody = cyFoot - r * 1.12f;
        float cyEye = cyBody - r * 0.30f;
        float eyeR = r * 0.30f;
        float bodyScaleY = 1f + 0.015f * breath;
        float bodyShift = -dp(0.6f) * (0.5f + 0.5f * breath);

        // 地面阴影
        paint.setColor(0x22000000);
        oval.set(cx - r * 1.0f, cyFoot + dp(2), cx + r * 1.0f, cyFoot + dp(10));
        c.drawOval(oval, paint);

        boolean gray = saturation < 0.999f;
        if (gray) {
            cm.setSaturation(Math.max(0f, saturation));
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
            c.saveLayer(0, 0, w, h, paint);
        } else {
            c.saveLayer(0, 0, w, h, null);
        }
        paint.setColorFilter(null);

        c.save();
        c.translate(shakeX, offsetY + bodyShift);
        if (Math.abs(scale - 1f) > 0.001f) c.scale(scale, scale, cx, cyFoot);
        if (Math.abs(rotation) > 0.01f) c.rotate(rotation, cx, cyFoot);
        if (Math.abs(hoverScale - 1f) > 0.001f) c.scale(hoverScale, hoverScale, cx, cyFoot);
        if (Math.abs(hoverTilt) > 0.01f) c.rotate(hoverTilt, cx, cyFoot);
        if (Math.abs(bodyScaleY - 1f) > 0.001f) c.scale(1f, bodyScaleY, cx, cyFoot);

        drawSprite(c);
        if (mode == Mode.CRY || mode == Mode.GULPING) drawTears(c, cx, cyEye, cyBody, r);
        if (thunderT > 0.02f && thunderT < 0.45f) {
            float flash = (float) Math.abs(Math.sin(thunderT * 40));
            paint.setColor(0xFFFFF6B0);
            paint.setAlpha((int) (150 * flash));
            c.drawCircle(cx, cyBody, r * 1.15f, paint);
            paint.setAlpha(255);
            stroke.setColor(0xFFFFF200);
            stroke.setStrokeWidth(dp(4));
            stroke.setAlpha((int) (255 * flash));
            path.reset();
            path.moveTo(cx - r * 0.1f, 0);
            path.lineTo(cx + r * 0.2f, cyBody - r * 0.6f);
            path.lineTo(cx - r * 0.25f, cyBody - r * 0.2f);
            path.lineTo(cx + r * 0.15f, cyBody + r * 0.1f);
            c.drawPath(path, stroke);
            stroke.setAlpha(255);
        }
        if (foodAlpha > 0.01f) drawFood(c, cx, cyBody, r);
        if (peeActive) {
            paint.setShader(null);
            paint.setColor(0xCCFFE95C);
            float px = cx;
            float py = cyFoot - r * 0.02f;
            for (int i = 0; i < 8; i++) {
                float t = (peeT * 1.4f + i * 0.13f) % 1f;
                float dx = -r * (0.15f + 0.6f * t);
                float dy = -(r * 0.5f) * (float) Math.sin(Math.PI * t) + r * 0.30f * t * t;
                float a = 1f - Math.max(0f, t - 0.75f) * 4f;
                paint.setAlpha((int) (220 * Math.max(0f, a)));
                c.drawCircle(px + dx, py + dy, r * 0.05f, paint);
            }
            paint.setAlpha(255);
        }
        c.restore();

        c.restore();

        // 粒子
        for (Particle p : particles) {
            drawParticle(c, p);
        }
    }

    private void drawSprite(Canvas c) {
        Bitmap bmp = bmpIdle;
        if (mode == Mode.LAUGH && bmpLaugh != null && bmpLaugh.length > 0) {
            bmp = bmpLaugh[laughFrame % bmpLaugh.length];
        } else if (blink < 0.98f && bmpBlink != null && bmpBlink.length > 0) {
            int fi = (int) ((1f - blink) * (bmpBlink.length - 1));
            fi = Math.max(0, Math.min(bmpBlink.length - 1, fi));
            bmp = bmpBlink[fi];
        } else if (bmpLook != null && bmpLook.length > 0) {
            int fi = (int) ((pupilX / 2.6f + 0.5f) * (bmpLook.length - 1));
            fi = Math.max(0, Math.min(bmpLook.length - 1, fi));
            bmp = bmpLook[fi];
        }
        if (bmp == null) return;
        int w = getWidth(), h = getHeight();
        float scale = Math.min(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
        float bw = bmp.getWidth() * scale;
        float bh = bmp.getHeight() * scale;
        float bx = (w - bw) / 2f;
        float by = (h - bh) / 2f;
        // 用独立不透明画笔，避免共享 paint 的 alpha 被其它绘制污染成半透明
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setAlpha(255);
        c.drawBitmap(bmp, null, new RectF(bx, by, bx + bw, by + bh), p);
    }

    private void drawFood(Canvas c, float cx, float cyBody, float r) {
        float fy = cyBody + r * 0.32f;
        float fx = cx + r * 0.10f;
        paint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, foodAlpha))));
        paint.setColor(C_FOOD);
        oval.set(fx - r * 0.16f * foodScale, fy - r * 0.10f * foodScale,
                fx + r * 0.16f * foodScale, fy + r * 0.10f * foodScale);
        c.drawOval(oval, paint);
        paint.setColor(C_BONE);
        oval.set(fx + r * 0.10f * foodScale, fy - r * 0.045f * foodScale,
                fx + r * 0.26f * foodScale, fy + r * 0.045f * foodScale);
        c.drawOval(oval, paint);
        paint.setAlpha(255);
    }

    private void drawTears(Canvas c, float cx, float cyEye, float cyBody, float r) {
        int w = getWidth(), h = getHeight();
        Bitmap ref = bmpIdle != null ? bmpIdle : (bmpLook != null && bmpLook.length > 0 ? bmpLook[0] : null);
        if (ref == null) return;
        float sc = Math.min(w / (float) ref.getWidth(), h / (float) ref.getHeight());
        float bw = ref.getWidth() * sc;
        float bh = ref.getHeight() * sc;
        float bx = (w - bw) / 2f;
        float by = (h - bh) / 2f;
        float eyeY = by + bh * 0.35f;
        float leftEyeX = bx + bw * 0.36f;   // 微向左移
        float rightEyeX = bx + bw * 0.60f;  // 微向左移
        // 两条泪滴：左小右大（1:2）
        drawTear(c, leftEyeX, eyeY, bh * 0.045f, 0.0f);
        drawTear(c, rightEyeX, eyeY, bh * 0.090f, 0.5f);
    }

    private void drawTear(Canvas c, float x, float y, float size, float phaseOffset) {
        float t = (cryPhase * 1.4f + phaseOffset) % 1.0f;
        float fall = t * getHeight() * 0.22f;
        float ty = y + fall;
        float alpha = 1f - t;
        if (alpha <= 0f) return;
        paint.setShader(null);
        paint.setColor(0xFF7EC8FF);
        paint.setAlpha((int) (255 * alpha));
        path.reset();
        path.moveTo(x, ty - size * 1.4f);
        path.quadTo(x + size, ty - size * 0.3f, x + size * 0.7f, ty + size * 0.5f);
        path.quadTo(x, ty + size * 1.1f, x - size * 0.7f, ty + size * 0.5f);
        path.quadTo(x - size, ty - size * 0.3f, x, ty - size * 1.4f);
        path.close();
        c.drawPath(path, paint);
        paint.setAlpha(255);
    }

    private void drawParticle(Canvas c, Particle p) {
        float a = Math.max(0f, Math.min(1f, p.life));
        if (p.kind == 0) {
            paint.setShader(null);
            paint.setColor(C_HEART);
            paint.setAlpha((int) (230 * a));
            path.reset();
            float s = p.size;
            path.moveTo(0, s * 0.30f);
            path.cubicTo(0, 0, -s, 0, -s, s * 0.45f);
            path.cubicTo(-s, s * 0.75f, 0, s * 0.90f, 0, s * 1.18f);
            path.cubicTo(0, s * 0.90f, s, s * 0.75f, s, s * 0.45f);
            path.cubicTo(s, 0, 0, 0, 0, s * 0.30f);
            c.save();
            c.translate(p.x, p.y);
            c.drawPath(path, paint);
            c.restore();
            paint.setAlpha(255);
        } else if (p.kind == 1) {
            textPaint.setColor(0xFFFFE066);
            textPaint.setAlpha((int) (255 * a));
            textPaint.setTextSize(p.size);
            c.drawText("★", p.x, p.y, textPaint);
            textPaint.setAlpha(255);
        } else if (p.kind == 2) {
            textPaint.setColor(0xFF8ED8FF);
            textPaint.setAlpha((int) (230 * a));
            textPaint.setTextSize(p.size);
            c.drawText("♪", p.x, p.y, textPaint);
            textPaint.setAlpha(255);
        } else if (p.kind == 3) {
            paint.setColor(0xDDF0F8FF);
            paint.setAlpha((int) (160 * a));
            c.drawCircle(p.x, p.y, p.size * a, paint);
            paint.setAlpha(255);
        } else if (p.kind == 4) {
            paint.setColor(0xFFB3122B);
            paint.setAlpha((int) (235 * a));
            c.drawCircle(p.x, p.y, p.size * a, paint);
            paint.setAlpha(255);
        } else if (p.kind == 5) {
            paint.setColor(C_POOP);
            paint.setAlpha((int) (230 * a));
            c.drawCircle(p.x, p.y, p.size, paint);
            paint.setAlpha(255);
        } else if (p.kind == 7) {
            paint.setColor(0xFFB0B0B0);
            paint.setAlpha((int) (85 * a));
            c.drawCircle(p.x, p.y, p.size * (1.9f - a), paint);
            paint.setAlpha(255);
        } else if (p.kind == 8) {
            paint.setColor(0xFFF4F4F0);
            paint.setAlpha((int) (230 * a));
            c.drawCircle(p.x, p.y, p.size, paint);
            paint.setAlpha(255);
        } else if (p.kind == 10) {
            paint.setColor(0xFFC98A4B);
            paint.setAlpha((int) (240 * a));
            c.drawCircle(p.x, p.y, p.size, paint);
            paint.setAlpha(255);
        } else if (p.kind == 9) {
            paint.setColor(0xFF3DF08A);
            paint.setAlpha((int) (255 * a));
            float s = p.size * (0.6f + 0.4f * a);
            path.reset();
            path.moveTo(p.x, p.y - s);
            path.lineTo(p.x + s, p.y);
            path.lineTo(p.x, p.y + s);
            path.lineTo(p.x - s, p.y);
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) (210 * a));
            c.drawCircle(p.x - s * 0.25f, p.y - s * 0.25f, s * 0.22f, paint);
            paint.setAlpha(255);
        } else {
            paint.setColor(0xFFFFE95C);
            paint.setAlpha((int) (200 * a));
            c.drawCircle(p.x, p.y, p.size, paint);
            paint.setAlpha(255);
        }
    }
}
