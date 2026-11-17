package com.naiwa.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * AI 对话界面：界面内聊天。右上角⚙️配置，可切到桌面气泡聊天。
 */
public class AiChatActivity extends Activity {

    private EditText input;
    private TextView log;
    private ScrollView scroll;
    private SharedPreferences prefs;
    private String apiKey = "", baseUrl = "https://api.deepseek.com", model = "deepseek-chat";
    private volatile boolean waiting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("naiwa_ai", MODE_PRIVATE);
        apiKey = prefs.getString("key", "");
        baseUrl = prefs.getString("url", "https://api.deepseek.com");
        model = prefs.getString("model", "deepseek-chat");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(12));
        root.setBackgroundColor(0xFF1B1B1B);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("🤖 p1 AI 对话");
        title.setTextColor(0xFFFFEB99);
        title.setTextSize(17);
        Button desk = new Button(this);
        desk.setText("💬桌面");
        desk.setAllCaps(false);
        desk.setOnClickListener(v -> finish());
        Button cfg = new Button(this);
        cfg.setText("⚙️");
        cfg.setAllCaps(false);
        cfg.setOnClickListener(v -> showSettings());
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(desk);
        top.addView(cfg);
        root.addView(top);

        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(0xFFEEEEEE);
        log.setTextSize(15);
        log.setLineSpacing(dp(4), 1f);
        log.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(log, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("跟p1说点啥…");
        input.setHintTextColor(0xFF777777);
        input.setTextColor(0xFFFFFFFF);
        input.setSingleLine(true);
        Button send = new Button(this);
        send.setText("发送");
        send.setAllCaps(false);
        send.setOnClickListener(v -> send());
        bottom.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(send);
        root.addView(bottom);

        setContentView(root);
        append("p1: 呱～我是p1！点右上角⚙️填钥匙就能聊天。想回桌面点「💬桌面」，长按p1菜单里也有「💬桌面聊天」。");
    }

    private void send() {
        String q = input.getText().toString().trim();
        if (q.isEmpty() || waiting) return;
        append("你: " + q);
        input.setText("");
        if (apiKey.trim().isEmpty()) {
            append("p1: 先点右上角⚙️填写 API Key 呱～");
            return;
        }
        if (Math.random() < 0.30) {
            append("p1: " + AiClient.randomReject());
            return;
        }
        waiting = true;
        append("p1: （思考中…）");
        new Thread(() -> {
            final String reply = AiClient.call(apiKey, baseUrl, model, q);
            runOnUiThread(() -> {
                removeLastLine();
                append("p1: " + reply);
                waiting = false;
            });
        }).start();
    }

    private void removeLastLine() {
        String txt = log.getText().toString();
        int idx = txt.lastIndexOf('\n');
        log.setText(idx >= 0 ? txt.substring(0, idx) : "");
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        EditText key = new EditText(this);
        key.setHint("API Key (sk-...)");
        key.setText(apiKey);
        EditText url = new EditText(this);
        url.setHint("Base URL");
        url.setText(baseUrl);
        EditText mdl = new EditText(this);
        mdl.setHint("模型名");
        mdl.setText(model);
        box.addView(key);
        box.addView(url);
        box.addView(mdl);
        new AlertDialog.Builder(this)
                .setTitle("AI 设置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    apiKey = key.getText().toString().trim();
                    baseUrl = url.getText().toString().trim();
                    if (baseUrl.isEmpty()) baseUrl = "https://api.deepseek.com";
                    model = mdl.getText().toString().trim();
                    if (model.isEmpty()) model = "deepseek-chat";
                    prefs.edit().putString("key", apiKey)
                            .putString("url", baseUrl)
                            .putString("model", model).apply();
                    append("p1: 设置已保存呱～模型=" + model + "，可留在这里聊，或点💬桌面去桌面聊。");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void append(String s) {
        String cur = log.getText().toString();
        log.setText(cur.isEmpty() ? s : cur + "\n" + s);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
