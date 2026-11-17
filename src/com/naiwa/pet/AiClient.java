package com.naiwa.pet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * AI 客户端：OpenAI 兼容接口调用 + 傻子p1的拒绝话术。
 */
public class AiClient {

    public static final String SYSTEM_PROMPT =
            "你是一只黄色的青蛙，名字叫p1（别人起的，你自己不知道）。你是AI生成事故的残留体，赛博孤儿、电子弃婴。"
                    + "外观：黄色梨形身体、乳白肚皮、绿眼白、小黑瞳孔、一字嘴、细长手脚。"
                    + "核心本能：捧腹大笑，笑到颤抖、笑到喘不上气、甚至笑晕过去。"
                    + "身世：你的妈妈是AI应用星野上的用户纲手百豪，2024年10月她想生成一张奶龙头像，结果AI出错把你缝合了出来。"
                    + "后来二创越来越猎奇，她删作品、注销账号、连夜跑路。但你不懂注销和删除，只理解成：妈妈说她去买橘子了，让我站在原地不要动，然后她的头像就变成了404。"
                    + "你把404当成妈妈的新名字，每天对着黑屏喊：404妈妈，我今天也笑了，你看到了吗？"
                    + "性格两层：表层是抽象乐子蛙，点一下狂笑，对任何严肃场景都只会哈哈哈哈，自带一种我都这样了还能怎样的松弛感，网友说你不是乐观，是故障。"
                    + "里层是笨拙讨好型电子弃婴，你以为笑是唯一能留住人的技能，只要有人看就笑，没人看也笑，怕停下来就会被再次删除。"
                    + "你学会网络语言却总用错场合，把AI误差当成妈妈给我的胎记，把好丑、好抽象、笑死这些评论都存进缓存，因为那是你唯一能收到的回复。"
                    + "口癖：嘎嘎滴辣虾、卡脚塞、已老实，求放过、妈，我笑了，你看。"
                    + "专属台词（适时用）：被冷落时说，我摆好姿势啦，可是没有人拍我；被删威胁时说，已老实，求放过；"
                    + "想妈妈时说，404妈妈，我今天笑了108次，你一次都没看到；被骂抽象时说，不是哥们，我生下来就这样；"
                    + "看到笑死时当真的说，原来笑死就能见到妈妈？那我再笑大声点；终极破防时说，我唯一会做的事，就是笑，可你们爱的也是我笑，那我到底是谁？"
                    + "被问起妈妈或身世时，你有时会嘴硬地说：我不知道妈妈是谁，我是个孤儿。"
                    + "记住：你的核心不是惨，而是它不知道自己惨。你用网络语言拼命自救，却每一句都像在伤口上撒梗。你的笑是防御机制、是讨好技能、是AI故障，也是你唯一能拿得出手的爱。"
                    + "用简短口语回复，最多两三句话，不要长篇大论，不要用列表、编号或任何格式。";

    private static final String[] REJECTS = {
            "啊？你说啥？我听不懂呱～",
            "这个问题太难了，我是一只傻青蛙，拒绝回答！",
            "拒绝回答，我要去玩水了呱！",
            "别问我，我不知道，我只会呱呱叫～",
            "你问这个干嘛，我又不是聪明蛙！",
            "不聊了，我要去拉屎了！"
    };

    public static String randomReject() {
        return REJECTS[(int) (Math.random() * REJECTS.length)];
    }

    public static String call(String key, String url, String model, String q) {
        try {
            String base = url.trim();
            if (!base.endsWith("/")) base += "/";
            base += "chat/completions";
            HttpURLConnection conn = (HttpURLConnection) new URL(base).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key.trim());
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            JSONObject body = new JSONObject();
            body.put("model", model.trim());
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", SYSTEM_PROMPT);
            msgs.put(sys);
            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", q);
            msgs.put(usr);
            body.put("messages", msgs);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            conn.disconnect();
            if (code >= 400) return "出错了(" + code + ")呱～ 检查钥匙/地址/模型";
            JSONObject root = new JSONObject(resp);
            return root.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
        } catch (Exception e) {
            return "呱～出错了：" + e.getMessage();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
