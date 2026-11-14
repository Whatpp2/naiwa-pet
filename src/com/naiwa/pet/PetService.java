package com.naiwa.pet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 悬浮窗服务：管理奶蛙（最多 6 只，可含 0.3 倍小个体）、按键移动、菜单、
 * 随机事件、交配产仔、死亡 5 秒后留下骷髅头（可清理/复活）。
 */
public class PetService extends Service {

    private static final String CH = "pet";
    private static final long LONG_PRESS_MS = 4000;
    private static final int MAX_PETS = 6;
    private static final int S_CROAK = 10;

    private WindowManager wm;
    private int petW, petH, screenW, screenH;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();

    private final List<Inst> pets = new ArrayList<>();
    private final List<Skull> skulls = new ArrayList<>();

    private LinearLayout menu;
    private WindowManager.LayoutParams menuParams;
    private Inst menuTarget;

    private LinearLayout skullMenu;
    private WindowManager.LayoutParams skullMenuParams;

    private SoundPool sp;
    private final int[] soundIds = new int[11];
    private android.content.SharedPreferences prefs;
    private View chatInput;
    private View restartButton;
    private float density = 3f;
    private float scaledDensity = 3f;

    private class Inst {
        PetView view;
        WindowManager.LayoutParams params;
        float sizeScale = 1f;
        float downRawX, downRawY;
        int startX, startY;
        boolean dragMode = true;
        boolean moved;
        boolean longPressed;
        int feedCount = 0;
        long lastClickSpeakAt = 0;
        long bornAt;
        Runnable ageDeathTask;
        Runnable growTask;
        TextView bubble;
        WindowManager.LayoutParams bubbleParams;
        Runnable bubbleHide;
        final Runnable longPress = () -> {
            longPressed = true;
            showMenu(Inst.this);
        };
        Runnable deathTask;
    }

    private class Skull {
        SkullView view;
        WindowManager.LayoutParams params;
        float sizeScale = 1f;
    }

    private static class Dropping {
        DroppingView view;
        WindowManager.LayoutParams params;
        int cx, cy;
        int type;
    }
    private final List<Dropping> droppings = new ArrayList<>();

    private final Runnable randomEvent = new Runnable() {
        @Override public void run() {
            if (!pets.isEmpty() && menu == null) {
                Inst inst = pets.get(rnd.nextInt(pets.size()));
                PetView v = inst.view;
                if (!v.isDead() && !v.isBusy()) {
                    int r = rnd.nextInt(100);
                    if (r < 26) v.dance();
                    else if (r < 44) v.cry();
                    else if (r < 62) v.sit();
                    else if (r < 80) v.poop();
                    else if (r < 93) v.pee();
                    else v.feed();
                }
            }
            scheduleRandom();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        petW = dp(200);
        petH = dp(220);
        prefs = getSharedPreferences("naiwa_ai", MODE_PRIVATE);
        diag("onCreate screenW=" + screenW + " screenH=" + screenH + " petW=" + petW
                + " density=" + density + " scaledDensity=" + scaledDensity);
        startForegroundNow();
        initSound();
        spawnPet(screenW - petW - dp(12), (int) (screenH * 0.28f), 1f).view.playSpawn();
        scheduleRandom();
    }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "桌宠运行状态", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("奶蛙桌宠")
                .setContentText("奶蛙正在屏幕上陪着你（长按打开菜单）")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void initSound() {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        sp = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(aa).build();
        soundIds[PetView.S_CLICK] = sp.load(this, R.raw.click, 1);
        soundIds[PetView.S_HAPPY] = sp.load(this, R.raw.happy, 1);
        soundIds[PetView.S_SAD] = sp.load(this, R.raw.sad, 1);
        soundIds[PetView.S_DEATH] = sp.load(this, R.raw.death, 1);
        soundIds[PetView.S_REVIVE] = sp.load(this, R.raw.revive, 1);
        soundIds[PetView.S_DANCE] = sp.load(this, R.raw.dance, 1);
        soundIds[PetView.S_POOP] = sp.load(this, R.raw.poop, 1);
        soundIds[PetView.S_PEE] = sp.load(this, R.raw.pee, 1);
        soundIds[PetView.S_EAT] = sp.load(this, R.raw.eat, 1);
        soundIds[S_CROAK] = sp.load(this, R.raw.croak, 1);
    }

    private void playSound(int type) {
        if (sp == null || type < 0 || type >= soundIds.length) return;
        int id = soundIds[type];
        if (id != 0) sp.play(id, 0.85f, 0.85f, 0, 0, 1f);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private int sp(float v) {
        return Math.round(v * scaledDensity);
    }

    private void diag(String msg) {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "diag.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            fos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignore) {
        }
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------- 生成宠物 ----------
    private Inst spawnPet(int x, int y, float sizeScale) {
        hideRestartButton();
        final Inst inst = new Inst();
        inst.sizeScale = sizeScale;
        inst.bornAt = SystemClock.uptimeMillis();
        int w = Math.round(petW * sizeScale);
        int h = Math.round(petH * sizeScale);

        PetView v = new PetView(this);
        v.setListener(new PetView.Listener() {
            @Override public void playSound(int type) {
                PetService.this.playSound(type);
            }
            @Override public void onBeingEatenFinished(PetView who) {
                handler.post(() -> removePet(who));
            }
            @Override public void onDied(PetView who) {
                startDeathCountdown(inst);
            }
            @Override public void onDrop(PetView who, int type) {
                spawnDropping(who, type);
            }
            @Override public void onThunderDied(PetView who) {
                startAshSkull(who);
            }
            @Override public void onAgeDied(PetView who) {
                startAgeSkull(who);
            }
        });

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = clamp(x, 0, screenW - w);
        p.y = clamp(y, 0, screenH - h);

        inst.view = v;
        inst.params = p;
        v.setOnTouchListener((vv, e) -> onPetTouch(inst, e));
        pets.add(inst);
        try {
            wm.addView(v, p);
        } catch (Exception e) {
            pets.remove(inst);
            if (pets.isEmpty()) stopSelf();
        }
        final int[] loc = new int[2];
        v.post(() -> {
            v.getLocationOnScreen(loc);
            diag("spawn params=(" + p.x + "," + p.y + ") loc=(" + loc[0] + "," + loc[1]
                    + ") size=" + w + "x" + h + " scale=" + sizeScale);
        });
        scheduleSpeak(inst);
        if (sizeScale < 1f) {
            inst.growTask = () -> {
                if (pets.contains(inst) && inst.sizeScale < 1f) growUp(inst);
            };
            handler.postDelayed(inst.growTask, 90000);
            handler.postDelayed(() -> speak(inst, "草，我在这里呢"), 400);
        } else {
            inst.ageDeathTask = () -> {
                if (pets.contains(inst) && inst.sizeScale >= 1f) ageDeath(inst);
            };
            handler.postDelayed(inst.ageDeathTask, 90000);
        }
        return inst;
    }

    private void startDeathCountdown(final Inst inst) {
        if (inst.deathTask != null) handler.removeCallbacks(inst.deathTask);
        final int dx = inst.params.x, dy = inst.params.y; // 死亡时的坐标
        inst.deathTask = () -> {
            if (!pets.contains(inst)) return;
            float sc = inst.sizeScale;
            removePet(inst.view);
            addSkull(dx, dy, sc, false, 0);
        };
        handler.postDelayed(inst.deathTask, 5000);
    }

    private boolean onPetTouch(Inst inst, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                inst.downRawX = e.getRawX();
                inst.downRawY = e.getRawY();
                inst.startX = inst.params.x;
                inst.startY = inst.params.y;
                inst.moved = false;
                inst.longPressed = false;
                handler.postDelayed(inst.longPress, 800); // 长按 0.8 秒打开菜单
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - inst.downRawX;
                float dy = e.getRawY() - inst.downRawY;
                double dist = Math.hypot(dx, dy);
                if (inst.dragMode) {
                    if (!inst.moved && dist > dp(10)) {
                        inst.moved = true;
                        handler.removeCallbacks(inst.longPress);
                    }
                    if (!inst.longPressed) {
                        inst.params.x = clamp((int) (inst.startX + dx), -inst.params.width / 4, screenW - inst.params.width * 3 / 4);
                        inst.params.y = clamp((int) (inst.startY + dy), 0, screenH - inst.params.height * 3 / 4);
                        wm.updateViewLayout(inst.view, inst.params);
                    }
                } else if (dist > dp(40)) {
                    // 只有明显滑动才取消长按，手指轻微抖动不影响
                    handler.removeCallbacks(inst.longPress);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(inst.longPress);
                if (inst.dragMode && inst.moved) {
                    return true; // 拖动结束，位置已实时更新
                }
                if (!inst.longPressed) {
                    inst.view.poke();
                    long now = SystemClock.uptimeMillis();
                    if (now - inst.lastClickSpeakAt >= 10000 && !inst.view.isDead()) {
                        inst.lastClickSpeakAt = now;
                        String key = prefs.getString("key", "");
                        if (!key.trim().isEmpty() && rnd.nextInt(100) < 30) {
                            aiSpeak(inst);
                        } else if (rnd.nextInt(100) < 20) {
                            speak(inst, "你摸我干吗，对我有非分之想吗");
                        } else {
                            speakRandom(inst);
                        }
                    }
                }
                return true;
        }
        return false;
    }

    // ---------- 移动（点击目标位置，3 秒跑过去） ----------
    private View moveLayer;

    private void enterMoveMode(final Inst inst) {
        if (moveLayer != null) return;
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x22000000);
        TextView hint = new TextView(this);
        hint.setText("👆 点击屏幕任意位置，奶蛙会跑过去");
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xCC1E1E1E);
        hb.setCornerRadius(dp(14));
        hint.setBackground(hb);
        hint.setPadding(dp(18), dp(10), dp(18), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(70);
        layer.addView(hint, lp);
        layer.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                final float px = e.getRawX();
                final float py = e.getRawY();
                handler.postDelayed(() -> {
                    removeMoveLayer();
                    runTo(inst, px, py);
                }, 30);
            }
            return true;
        });

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        moveLayer = layer;
        try {
            wm.addView(layer, p);
        } catch (Exception e) {
            moveLayer = null;
        }
    }

    private void removeMoveLayer() {
        if (moveLayer == null) return;
        try {
            wm.removeView(moveLayer);
        } catch (Exception ignore) {
        }
        moveLayer = null;
    }

    private void reposition(Inst inst, int x, int y) {
        int w = inst.params.width;
        int h = inst.params.height;
        inst.params.x = clamp(x, 0, screenW - w);
        inst.params.y = clamp(y, 0, screenH - h);
        // 本机 updateViewLayout 对悬浮窗不可靠：改为 remove + add 强制落位
        try {
            wm.removeView(inst.view);
        } catch (Exception ignore) {
        }
        try {
            wm.addView(inst.view, inst.params);
        } catch (Exception e) {
            try {
                wm.updateViewLayout(inst.view, inst.params);
            } catch (Exception ignore2) {
            }
        }
    }

    private void runTo(final Inst inst, float tx, float ty) {
        int w = inst.params.width;
        int h = inst.params.height;
        runToAnim(inst, clamp((int) (tx - w / 2f), 0, screenW - w),
                clamp((int) (ty - h / 2f), 0, screenH - h), null);
    }

    private void runToAnim(final Inst inst, final int destX, final int destY, final Runnable onArrive) {
        final int sx = inst.params.x, sy = inst.params.y;
        final long startAt = SystemClock.uptimeMillis();
        final long duration = 1200;
        final int dir = destX >= sx ? 1 : -1;
        inst.view.startRunning(dir);
        final Runnable step = new Runnable() {
            @Override public void run() {
                if (!pets.contains(inst)) { inst.view.stopRunning(); return; }
                long now = SystemClock.uptimeMillis();
                float t = Math.min(1f, (now - startAt) / (float) duration);
                float e = t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;
                inst.params.x = (int) (sx + (destX - sx) * e);
                inst.params.y = (int) (sy + (destY - sy) * e);
                try { wm.updateViewLayout(inst.view, inst.params); } catch (Exception ignore) {}
                if (t < 1f) {
                    handler.postDelayed(this, 16);
                } else {
                    inst.view.stopRunning();
                    if (onArrive != null) onArrive.run();
                }
            }
        };
        handler.post(step);
    }

    // ---------- 召唤 / 交配 / 吃同类 ----------
    private void spawnNear(Inst target, float sizeScale) {
        if (pets.size() >= MAX_PETS) return;
        float body = Math.max(petW, petH) * Math.max(0.3f, target.sizeScale); // 一个身位
        int w = Math.round(petW * sizeScale);
        int h = Math.round(petH * sizeScale);
        int fx = -1, fy = -1;
        for (int attempt = 0; attempt < 40; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            int dist = Math.round(body * (0.9f + rnd.nextFloat() * 0.5f));
            int x = clamp((int) (target.params.x + Math.cos(ang) * dist), 0, screenW - w);
            int y = clamp((int) (target.params.y + Math.sin(ang) * dist), 0, screenH - h);
            if (!overlapsAny(x, y, w, h)) {
                fx = x; fy = y;
                break;
            }
            fx = x; fy = y;
        }
        if (fx < 0) return;
        Inst inst = spawnPet(fx, fy, sizeScale);
        inst.view.playSpawn();
    }

    private boolean overlapsAny(int x, int y, int w, int h) {
        for (Inst i : pets) {
            int ox = i.params.x, oy = i.params.y;
            int ow = i.params.width, oh = i.params.height;
            if (x < ox + ow && x + w > ox && y < oy + oh && y + h > oy) {
                return true;
            }
        }
        return false;
    }

    private void mate(Inst target) {
        if (target == null) return;
        if (pets.size() == 1) {
            target.view.soloPlay(); // 孤单自娱 + 产生白色液体
            speak(target, "允许起飞");
            return;
        }
        if (pets.size() >= MAX_PETS) return;
        Inst a = target;
        Inst b = null;
        for (Inst i : pets) {
            if (i != a) { b = i; break; }
        }
        if (b == null) return;

        final boolean crossSize = Math.abs(a.sizeScale - b.sizeScale) > 0.5f;

        final boolean moveA = rnd.nextBoolean();
        final Inst mover = moveA ? a : b;
        final Inst anchor = moveA ? b : a;
        final int mx, my;
        if (crossSize) {
            // 跨体型交配：位移到对方中心（完全重叠）
            mx = clamp(anchor.params.x + anchor.params.width / 2 - mover.params.width / 2, 0, screenW - mover.params.width);
            my = clamp(anchor.params.y + anchor.params.height / 2 - mover.params.height / 2, 0, screenH - mover.params.height);
        } else {
            double ang = rnd.nextDouble() * Math.PI * 2;
            int halfBody = Math.round(petW * anchor.sizeScale * 0.5f);
            mx = clamp(anchor.params.x + (int) (Math.cos(ang) * halfBody), 0, screenW - mover.params.width);
            my = clamp(anchor.params.y + (int) (Math.sin(ang) * halfBody), 0, screenH - mover.params.height);
        }
        final int msx = mover.params.x, msy = mover.params.y;
        final ValueAnimator mv = ValueAnimator.ofFloat(0f, 1f);
        mv.setDuration(600);
        mv.addUpdateListener(x -> {
            float t = (float) x.getAnimatedValue();
            mover.params.x = (int) (msx + (mx - msx) * t);
            mover.params.y = (int) (msy + (my - msy) * t);
            try { wm.updateViewLayout(mover.view, mover.params); } catch (Exception ignore) {}
        });
        mv.start();

        mover.view.mate();
        anchor.view.mate();
        if (rnd.nextInt(100) < 70) speak(a, "哎呀我草");
        if (rnd.nextInt(100) < 70) speak(b, "哎呀我草");

        final Inst pairA = a;
        final Inst pairB = b;
        handler.postDelayed(() -> {
            if (!pets.contains(pairA) || !pets.contains(pairB)) return;
            if (pets.size() >= MAX_PETS) return;
            Inst below = pairB;
            int cx = clamp(below.params.x + rnd.nextInt(dp(50)) - dp(25), 0, screenW - dp(60));
            int cy = clamp(below.params.y + below.params.height + dp(4), 0, screenH - dp(66));
            Inst child = spawnPet(cx, cy, 0.3f);
            child.view.playSpawn();
            if (crossSize) {
                Inst big = pairA.sizeScale > pairB.sizeScale ? pairA : pairB;
                big.view.thunderDeath();
            }
        }, 4000);
    }

    private void eatSameKind(Inst target) {
        if (pets.size() < 2 || target == null) return;
        Inst a = target;
        Inst b = null;
        for (Inst i : pets) {
            if (i != a) { b = i; break; }
        }
        if (b == null) return;
        final Inst eater = a;
        final Inst victim = b;
        int destX = clamp(victim.params.x + victim.params.width / 2 - eater.params.width / 2, 0, screenW - eater.params.width);
        int destY = clamp(victim.params.y + victim.params.height / 2 - eater.params.height / 2, 0, screenH - eater.params.height);
        // 进食者先跑到被吃者位置，再吃
        runToAnim(eater, destX, destY, () -> {
            if (!pets.contains(eater) || !pets.contains(victim)) return;
            eater.view.playEat();
            victim.view.playBeingEaten();
            if (eater.sizeScale < 1f) {
                handler.postDelayed(() -> {
                    if (pets.contains(eater) && eater.sizeScale < 1f) growUp(eater);
                }, 900);
            }
        });
    }

    private void feedInst(Inst inst) {
        inst.view.feed();
        if (inst.sizeScale < 1f) {
            inst.feedCount++;
            if (inst.feedCount >= 3) {
                inst.feedCount = 0;
                handler.postDelayed(() -> {
                    if (pets.contains(inst) && inst.sizeScale < 1f) growUp(inst);
                }, 1300);
            }
        }
    }

    private void growUp(Inst inst) {
        if (inst.sizeScale >= 1f) return;
        int oldW = inst.params.width, oldH = inst.params.height;
        int cx = inst.params.x + oldW / 2;
        int cy = inst.params.y + oldH / 2;
        inst.sizeScale = 1f;
        if (inst.growTask != null) handler.removeCallbacks(inst.growTask);
        inst.growTask = null;
        resizeAndMove(inst, petW, petH, cx - petW / 2, cy - petH / 2);
        inst.view.playGrowUp();
        speak(inst, "还是小孩舒服呀");
        inst.ageDeathTask = () -> {
            if (pets.contains(inst) && inst.sizeScale >= 1f) ageDeath(inst);
        };
        handler.postDelayed(inst.ageDeathTask, 90000);
    }

    private void resizeAndMove(Inst inst, int w, int h, int x, int y) {
        inst.params.width = w;
        inst.params.height = h;
        inst.params.x = clamp(x, 0, screenW - w);
        inst.params.y = clamp(y, 0, screenH - h);
        try {
            wm.updateViewLayout(inst.view, inst.params);
        } catch (Exception e) {
            try { wm.removeView(inst.view); } catch (Exception ignore) {}
            try { wm.addView(inst.view, inst.params); } catch (Exception ignore2) {}
        }
    }

    private void spawnDropping(PetView who, int type) {
        if (droppings.size() >= 12) {
            Dropping old = droppings.remove(0);
            try { wm.removeView(old.view); } catch (Exception ignore) {}
        }
        int[] loc = new int[2];
        who.getLocationOnScreen(loc);
        int px = loc[0] + who.getWidth() / 2 + (rnd.nextInt(dp(50)) - dp(25));
        int py = loc[1] + (int) (who.getHeight() * 0.82f);
        int w, h;
        if (type == PetView.D_PEE) { w = dp(64); h = dp(20); }
        else if (type == PetView.D_WHITE) { w = dp(56); h = dp(24); }
        else { w = dp(44); h = dp(38); }
        Dropping d = new Dropping();
        d.type = type;
        d.cx = px;
        d.cy = py;
        DroppingView v = new DroppingView(this, type);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = clamp(px - w / 2, 0, screenW - w);
        p.y = clamp(py - h / 2, 0, screenH - h);
        d.view = v;
        d.params = p;
        droppings.add(d);
        try {
            wm.addView(v, p);
        } catch (Exception e) {
            droppings.remove(d);
        }
    }

    private void eatDroppingAction(Inst inst) {
        if (droppings.isEmpty()) return;
        int[] loc = new int[2];
        inst.view.getLocationOnScreen(loc);
        int px = loc[0] + inst.view.getWidth() / 2;
        int py = loc[1] + inst.view.getHeight() / 2;
        Dropping nearest = null;
        double best = Double.MAX_VALUE;
        for (Dropping d : droppings) {
            double dist = Math.hypot(d.cx - px, d.cy - py);
            if (dist < best) {
                best = dist;
                nearest = d;
            }
        }
        if (nearest != null) {
            int type = nearest.type;
            droppings.remove(nearest);
            try { wm.removeView(nearest.view); } catch (Exception ignore) {}
            inst.view.playEatDropping(type);
            if (rnd.nextBoolean()) speak(inst, "美汁汁");
        }
    }

    private void scheduleSpeak(Inst inst) {
        handler.postDelayed(() -> {
            if (pets.contains(inst) && !inst.view.isDead()) {
                speakRandom(inst);
                scheduleSpeak(inst);
            }
        }, 10000 + rnd.nextInt(40000));
    }

    private void aiSpeak(Inst inst) {
        String key = prefs.getString("key", "");
        String url = prefs.getString("url", "https://api.deepseek.com");
        String model = prefs.getString("model", "deepseek-chat");
        String q = "突然随便说一句傻话，不超过15个字";
        new Thread(() -> {
            final String raw = AiClient.call(key, url, model, q);
            String reply = raw.trim().replaceAll("[\\n\\r]+", "");
            if (reply.codePointCount(0, reply.length()) > 15) {
                int end = reply.offsetByCodePoints(0, 15);
                reply = reply.substring(0, end);
            }
            final String r = reply;
            handler.post(() -> {
                if (pets.contains(inst)) speak(inst, r);
            });
        }).start();
    }

    private void speakRandom(Inst inst) {
        String[] small = {"原神牛逼", "你瞅啥", "我爱你鸭", "我是个悲子", "杂鱼，杂鱼，杂鱼！"};
        String[] big = {"阿米洛斯", "我不是萝莉控", "我饿了", "我快老死了，我还是个处", "我是个乐子"};
        String[] arr = inst.sizeScale < 1f ? small : big;
        speak(inst, arr[rnd.nextInt(arr.length)]);
    }

    private void speak(Inst inst, String text) {
        int len = text.codePointCount(0, text.length());
        long ms = 3000 + len * 500;
        showBubble(inst, text, ms);
        playCroak(len); // 每个字播一声笑，长度随字数
        inst.view.laugh();
        resetAge(inst);
    }

    private void showBubble(Inst inst, String text, long ms) {
        hideBubble(inst);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(dp(12));
        tv.setBackground(bg);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setMaxWidth(screenW - dp(24));
        tv.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = tv.getMeasuredWidth();
        int bh = tv.getMeasuredHeight();
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        int px = inst.params.x + inst.params.width / 2 - bw / 2;
        int py = inst.params.y - bh - dp(4);
        p.x = clamp(px, 0, screenW - bw);
        p.y = clamp(py, 0, screenH - bh);
        inst.bubble = tv;
        inst.bubbleParams = p;
        try {
            wm.addView(tv, p);
        } catch (Exception e) {
            inst.bubble = null;
            return;
        }
        inst.bubbleHide = () -> hideBubble(inst);
        handler.postDelayed(inst.bubbleHide, ms);
    }

    private void hideBubble(Inst inst) {
        if (inst.bubble == null) return;
        if (inst.bubbleHide != null) handler.removeCallbacks(inst.bubbleHide);
        try { wm.removeView(inst.bubble); } catch (Exception ignore) {}
        inst.bubble = null;
        inst.bubbleHide = null;
    }

    private void playCroak(int n) {
        for (int i = 0; i < n; i++) {
            handler.postDelayed(() -> {
                int id = soundIds[S_CROAK];
                if (sp != null && id != 0) sp.play(id, 0.9f, 0.9f, 0, 0, 1f);
            }, i * 260L);
        }
    }

    private void ageDeath(Inst inst) {
        if (!pets.contains(inst) || inst.view.isDead()) return;
        inst.view.ageDeath();
    }

    private void startAgeSkull(PetView who) {
        Inst inst = null;
        for (Inst i : pets) {
            if (i.view == who) { inst = i; break; }
        }
        if (inst == null) return;
        int x = inst.params.x, y = inst.params.y;
        float sc = inst.sizeScale;
        if (inst.deathTask != null) handler.removeCallbacks(inst.deathTask);
        removePet(inst.view);
        addSkull(x, y, sc, false, 7000); // 普通骷髅 7 秒清除
    }

    private void resetAge(Inst inst) {
        if (inst.ageDeathTask != null) {
            handler.removeCallbacks(inst.ageDeathTask);
            inst.ageDeathTask = () -> {
                if (pets.contains(inst) && inst.sizeScale >= 1f) ageDeath(inst);
            };
            handler.postDelayed(inst.ageDeathTask, 90000);
        }
    }

    private Inst pickTalker() {
        Inst best = null;
        for (Inst i : pets) {
            if (i.view.isDead()) continue;
            if (best == null || i.bornAt < best.bornAt) best = i;
        }
        return best;
    }

    private void showRestartButton() {
        if (restartButton != null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE01E1E1E);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText("💀 奶蛙都死了");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        title.setGravity(Gravity.CENTER);
        Button b = new Button(this);
        b.setText("🔄 重新开始");
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            hideRestartButton();
            spawnPet(screenW / 2 - petW / 2, screenH / 2 - petH / 2, 1f).view.playSpawn();
        });
        box.addView(title);
        box.addView(b, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        restartButton = box;
        try {
            wm.addView(box, p);
        } catch (Exception e) {
            restartButton = null;
        }
    }

    private void hideRestartButton() {
        if (restartButton == null) return;
        try { wm.removeView(restartButton); } catch (Exception ignore) {}
        restartButton = null;
    }

    private void showChatInput() {
        if (chatInput != null) {
            hideChatInput();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE01E1E1E);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        EditText et = new EditText(this);
        et.setHint("跟奶蛙说…");
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF888888);
        et.setSingleLine(true);
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        et.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                doSend(et);
                return true;
            }
            return false;
        });
        Button send = new Button(this);
        send.setText("发送");
        send.setAllCaps(false);
        send.setOnClickListener(v -> doSend(et));
        box.addView(et, new LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(send);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = (int) (screenH * 0.15f);
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        chatInput = box;
        try {
            wm.addView(box, p);
            et.requestFocus();
            handler.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }, 200);
        } catch (Exception e) {
            chatInput = null;
        }
    }

    private void hideChatInput() {
        if (chatInput == null) return;
        try { wm.removeView(chatInput); } catch (Exception ignore) {}
        chatInput = null;
    }

    private void doSend(EditText et) {
        String q = et.getText().toString().trim();
        if (q.isEmpty()) return;
        et.setText("");
        hideChatInput();
        sendChat(q);
    }

    private void sendChat(String q) {
        Inst talker = pickTalker();
        if (talker == null) return;
        String key = prefs.getString("key", "");
        String url = prefs.getString("url", "https://api.deepseek.com");
        String model = prefs.getString("model", "deepseek-chat");
        if (key.trim().isEmpty()) {
            speak(talker, "先打开🤖AI对话填好钥匙呱～");
            return;
        }
        if (Math.random() < 0.30) {
            speak(talker, AiClient.randomReject());
            return;
        }
        new Thread(() -> {
            final String reply = AiClient.call(key, url, model, q);
            handler.post(() -> {
                if (pets.contains(talker)) speak(talker, reply);
            });
        }).start();
    }

    private void removePet(PetView v) {
        for (int i = 0; i < pets.size(); i++) {
            if (pets.get(i).view == v) {
                Inst inst = pets.remove(i);
                if (inst.deathTask != null) handler.removeCallbacks(inst.deathTask);
                try {
                    wm.removeView(inst.view);
                } catch (Exception ignore) {
                }
                break;
            }
        }
        if (menuTarget != null && menuTarget.view == v) hideMenu();
        if (pets.isEmpty()) showRestartButton();
    }

    // ---------- 骷髅 ----------
    private void addSkull(int x, int y, float sizeScale, boolean ashes, long disappearMs) {
        Skull s = new Skull();
        s.sizeScale = sizeScale;
        int w = Math.round(dp(46) * sizeScale);
        int h = Math.round(dp(52) * sizeScale);
        SkullView v = new SkullView(this, ashes);
        v.setOnClickListener(vv -> showSkullMenu(s));
        v.setOnLongClickListener(vv -> {
            showSkullMenu(s);
            return true;
        });
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = clamp(x, 0, screenW - w);
        p.y = clamp(y, 0, screenH - h);
        s.view = v;
        s.params = p;
        skulls.add(s);
        try {
            wm.addView(v, p);
        } catch (Exception e) {
            skulls.remove(s);
        }
        if (disappearMs > 0) {
            handler.postDelayed(() -> removeSkull(s), disappearMs);
        }
    }

    private void startAshSkull(PetView who) {
        Inst inst = null;
        for (Inst i : pets) {
            if (i.view == who) { inst = i; break; }
        }
        if (inst == null) return;
        int x = inst.params.x, y = inst.params.y;
        float sc = inst.sizeScale;
        if (inst.deathTask != null) handler.removeCallbacks(inst.deathTask);
        removePet(inst.view);
        addSkull(x, y, sc, true, 1000); // 白灰骷髅，1 秒后消失
    }

    private void removeSkull(Skull s) {
        skulls.remove(s);
        try {
            wm.removeView(s.view);
        } catch (Exception ignore) {
        }
        hideSkullMenu();
    }

    private void showSkullMenu(final Skull s) {
        hideSkullMenu();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2262626);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x66FFFFFF);
        root.setBackground(bg);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("💀 奶蛙的遗骸");
        title.setTextColor(0xFFFFEB99);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView clean = makeMenuText("🧹 清理掉");
        clean.setOnClickListener(v -> removeSkull(s));
        root.addView(clean, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView revive = makeMenuText("✨ 复活它");
        revive.setOnClickListener(v -> {
            int x = s.params.x, y = s.params.y;
            float sc = s.sizeScale;
            removeSkull(s);
            Inst inst = spawnPet(x, y, sc);
            inst.view.playSpawn();
            playSound(PetView.S_REVIVE);
        });
        root.addView(revive, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                hideSkullMenu();
                return true;
            }
            return false;
        });

        skullMenuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        skullMenuParams.gravity = Gravity.CENTER;
        skullMenu = root;
        root.setAlpha(0f);
        try {
            wm.addView(root, skullMenuParams);
            root.animate().alpha(1f).setDuration(150).start();
        } catch (Exception e) {
            skullMenu = null;
        }
    }

    private void hideSkullMenu() {
        if (skullMenu == null) return;
        try {
            wm.removeView(skullMenu);
        } catch (Exception ignore) {
        }
        skullMenu = null;
    }

    private TextView makeMenuText(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- 主菜单 ----------
    private void showMenu(Inst target) {
        if (menu != null) {
            hideMenu();
            return;
        }
        menuTarget = target;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21E1E1E);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x66FFFFFF);
        root.setBackground(bg);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText(pets.size() > 1 ? "🐸 奶蛙菜单（" + pets.size() + " 只）" : "🐸 奶蛙菜单");
        title.setTextColor(0xFFFFEB99);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(17));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final PetView pv = target.view;
        addBtn(grid, "🕺 跳舞", pv::dance);
        addBtn(grid, "😭 哭泣", pv::cry);
        addBtn(grid, "🍗 喂食", () -> feedInst(target));
        addBtn(grid, "💩 拉屎", pv::poop);
        addBtn(grid, "🚽 尿尿", pv::pee);
        addBtn(grid, "🍽️ 吃排泄物", () -> eatDroppingAction(target));
        addBtn(grid, "🧘 坐下", pv::sit);
        addBtn(grid, target.dragMode ? "✋ 拖动：开" : "✋ 拖动：关", () -> {
            target.dragMode = !target.dragMode;
            Toast.makeText(PetService.this,
                    target.dragMode ? "拖动已开启：按住奶蛙即可移动" : "拖动已关闭",
                    Toast.LENGTH_SHORT).show();
        });
        addBtn(grid, "💞 交配", () -> mate(target));
        addBtn(grid, "👥 召唤同类", () -> spawnNear(target, 1f));
        addBtn(grid, "🦖 吃掉同类", () -> eatSameKind(target));
        addBtn(grid, "🏃 点击移动", () -> enterMoveMode(target));
        addBtn(grid, "🤖 AI 对话", this::openAiChat);
        addBtn(grid, "💬 桌面聊天", this::showChatInput);
        addBtn(grid, "💀 杀死", () -> pv.kill());
        addBtn(grid, "✨ 复活", () -> reviveInst(target));
        addBtn(grid, "👋 退出", this::stopSelf);

        root.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                hideMenu();
                return true;
            }
            return false;
        });

        root.measure(View.MeasureSpec.makeMeasureSpec(dp(340), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int mh = Math.max(dp(60), root.getMeasuredHeight());
        menuParams = new WindowManager.LayoutParams(
                dp(340),
                mh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.CENTER;
        menu = root;
        root.setAlpha(0f);
        try {
            wm.addView(menu, menuParams);
            root.animate().alpha(1f).setDuration(160).start();
        } catch (Exception e) {
            menu = null;
        }
    }

    private void openAiChat() {
        Intent i = new Intent(this, AiChatActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void reviveInst(Inst inst) {
        if (inst.deathTask != null) {
            handler.removeCallbacks(inst.deathTask);
            inst.deathTask = null;
        }
        inst.view.revive();
    }

    private void addBtn(GridLayout grid, String text, Runnable action) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(4), dp(10), dp(4), dp(10));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(148);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setOnClickListener(v -> {
            hideMenu();
            action.run();
        });
        grid.addView(b, lp);
    }

    private void hideMenu() {
        if (menu == null) return;
        try {
            wm.removeView(menu);
        } catch (Exception ignore) {
        }
        menu = null;
        menuTarget = null;
    }

    private void scheduleRandom() {
        handler.removeCallbacks(randomEvent);
        handler.postDelayed(randomEvent, 8000 + rnd.nextInt(9000));
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideMenu();
        hideSkullMenu();
        hideChatInput();
        hideRestartButton();
        for (Inst inst : pets) {
            hideBubble(inst);
            try {
                wm.removeView(inst.view);
            } catch (Exception ignore) {
            }
        }
        pets.clear();
        for (Skull s : skulls) {
            try {
                wm.removeView(s.view);
            } catch (Exception ignore) {
            }
        }
        skulls.clear();
        for (Dropping d : droppings) {
            try {
                wm.removeView(d.view);
            } catch (Exception ignore) {
            }
        }
        droppings.clear();
        if (sp != null) {
            sp.release();
            sp = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
