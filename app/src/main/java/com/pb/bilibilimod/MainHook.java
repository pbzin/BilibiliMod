package com.pb.bilibilimod;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "BiliBiliMod";
    private static final String BILIBILI_PACKAGE = "tv.danmaku.bili";

    private static volatile Activity bilibiliActivity;
    private static volatile boolean bilibiliActivityRefreshScheduled;
    private static volatile boolean bilibiliActivityHooked;
    private static volatile long lastBilibiliActivityRefreshMs;
    private static final Map<String, String> bilibiliCategoryTitleCache = new ConcurrentHashMap<>();
    private static final Map<String, String> bilibiliCategoryTitleTextCache = new ConcurrentHashMap<>();
    private static final Map<String, String> bilibiliAuthorTitleCache = new ConcurrentHashMap<>();
    private static final Map<String, String> bilibiliAuthorTitleTextCache = new ConcurrentHashMap<>();
    private static final String BILIBILI_TRANSLATION_PENDING = "\u0000pending";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!BILIBILI_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("loaded " + lpparam.packageName + " process=" + lpparam.processName);
        hookBilibiliRegion(lpparam.classLoader);
    }

    private static void hookBilibiliRegion(ClassLoader classLoader) {
        try {
            Class<?> regionManager = XposedHelpers.findClass(
                    "com.bilibili.region.RegionManager", classLoader);

            XposedHelpers.findAndHookMethod(regionManager, "getIpRegion",
                    XC_MethodReplacement.returnConstant("CN"));
            XposedHelpers.findAndHookMethod(regionManager, "getLegalRegion",
                    XC_MethodReplacement.returnConstant("CN"));
            XposedHelpers.findAndHookMethod(regionManager, "getRecentRegion",
                    XC_MethodReplacement.returnConstant("CN"));
            XposedHelpers.findAndHookMethod(regionManager, "currentRegion",
                    XC_MethodReplacement.returnConstant("CN"));
            XposedHelpers.findAndHookMethod(regionManager, "isChina",
                    XC_MethodReplacement.returnConstant(true));

            hookBilibiliRegionSetter(regionManager, "setIpRegion");
            hookBilibiliRegionSetter(regionManager, "setLegalRegion");
            hookBilibiliRegionSetter(regionManager, "setRecentRegion");
            log("hooked Bilibili RegionManager as CN");
        } catch (Throwable t) {
            log("Bilibili RegionManager hook failed", t);
        }

        hookBilibiliTopMore(classLoader);
        hookBilibiliSubtitlePreferences(classLoader);
        hookBilibiliTranslationDiagnostics(classLoader);
        hookBilibiliCategoryTitleTranslation(classLoader);
        hookBilibiliAuthorSpaceTitleTranslation(classLoader);
        hookBilibiliActivityRefresh();
    }

    private static void hookBilibiliTopMore(ClassLoader classLoader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "tv.danmaku.bili.ui.main2.resource.MainResourceManager", classLoader);
            XposedHelpers.findAndHookMethod(manager, "t", List.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = ensureBilibiliChannelTab(classLoader, (List<?>) param.args[0]);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (result instanceof List && !hasBilibiliChannelEntry((List<?>) result)) {
                        List<Object> patched = new ArrayList<>((List<?>) result);
                        patched.add(newBilibiliMoreEntry(classLoader));
                        param.setResult(patched);
                        log("patched Bilibili top_more result with channel entry");
                    }
                }
            });
            log("hooked Bilibili top_more channel patch");
        } catch (Throwable t) {
            log("Bilibili top_more hook failed", t);
        }
    }

    private static void hookBilibiliSubtitlePreferences(ClassLoader classLoader) {
        try {
            Class<?> subtitleMossKtx = XposedHelpers.findClass(
                    "com.bapis.bilibili.subtitle.SubtitleMossKtxKt", classLoader);
            Class<?> subtitleMoss = XposedHelpers.findClass(
                    "com.bapis.bilibili.subtitle.SubtitleMoss", classLoader);
            Class<?> subtitleViewReq = XposedHelpers.findClass(
                    "com.bapis.bilibili.subtitle.SubtitleViewReq", classLoader);
            Class<?> continuation = XposedHelpers.findClass(
                    "kotlin.coroutines.Continuation", classLoader);

            XposedHelpers.findAndHookMethod(subtitleMossKtx, "suspendSubtitleView",
                    subtitleMoss, subtitleViewReq, continuation, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object req = param.args[1];
                            forceBilibiliPreferredSubtitleLanguage(req);
                            log("Bilibili subtitle req " + describeBilibiliSubtitleRequest(req));
                        }
                    });
            log("hooked Bilibili subtitle request preferences");
        } catch (Throwable t) {
            log("Bilibili subtitle request hook failed", t);
        }

        try {
            Class<?> responseHandler = XposedHelpers.findClass(
                    "com.bapis.bilibili.subtitle.SubtitleMossKtxKt$suspendSubtitleView$$inlined$suspendCall$1",
                    classLoader);
            XposedHelpers.findAndHookMethod(responseHandler, "onNext", Object.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("Bilibili subtitle reply " + describeBilibiliSubtitleReply(param.args[0]));
                }
            });
            log("hooked Bilibili subtitle reply diagnostics");
        } catch (Throwable t) {
            log("Bilibili subtitle reply hook failed", t);
        }
    }

    private static void hookBilibiliTranslationDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> translationMoss = XposedHelpers.findClass(
                    "com.bapis.bilibili.app.translation.v1.TranslationMoss", classLoader);
            Class<?> simpleReq = XposedHelpers.findClass(
                    "com.bapis.bilibili.app.translation.v1.TranslationSimpleReq", classLoader);

            XposedHelpers.findAndHookMethod(translationMoss, "executeTranslationSimple",
                    simpleReq, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            log("Bilibili translation req " + describeBilibiliTranslationRequest(param.args[0]));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("Bilibili translation reply " + describeBilibiliTranslationReply(param.getResult()));
                        }
                    });
            log("hooked Bilibili translation diagnostics");
        } catch (Throwable t) {
            log("Bilibili translation diagnostics hook failed", t);
        }
    }

    private static void hookBilibiliCategoryTitleTranslation(ClassLoader classLoader) {
        try {
            Class<?> categoryAv = XposedHelpers.findClass("Zj0.a$a", classLoader);
            XposedHelpers.findAndHookConstructor(categoryAv,
                    String.class, String.class, String.class, String.class, String.class,
                    String.class, String.class, long.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object titleObject = param.args[9];
                            if (!(titleObject instanceof String)) {
                                return;
                            }

                            String title = (String) titleObject;
                            if (isEmpty(title) || !containsCjk(title)) {
                                return;
                            }

                            long aid = param.args[7] instanceof Number
                                    ? ((Number) param.args[7]).longValue()
                                    : 0L;
                            String key = aid + "|" + title;
                            String cached = bilibiliCategoryTitleCache.get(key);
                            if (isEmpty(cached) || BILIBILI_TRANSLATION_PENDING.equals(cached)) {
                                cached = bilibiliCategoryTitleTextCache.get(title);
                            }
                            if (!isEmpty(cached) && !BILIBILI_TRANSLATION_PENDING.equals(cached)) {
                                param.args[9] = cached;
                                return;
                            }

                            if (Looper.myLooper() == Looper.getMainLooper()) {
                                if (cached == null) {
                                    bilibiliCategoryTitleCache.put(key, BILIBILI_TRANSLATION_PENDING);
                                    translateBilibiliCategoryTitleAsync(classLoader, null, key, aid, title);
                                }
                                return;
                            }

                            try {
                                String translated = requestBilibiliTitleTranslation(classLoader, aid, title);
                                if (!isEmpty(translated) && !translated.equals(title)) {
                                    bilibiliCategoryTitleCache.put(key, translated);
                                    param.args[9] = translated;
                                    log("Bilibili category title pretranslated aid=" + aid
                                            + " title=" + title + " -> " + translated);
                                }
                            } catch (Throwable t) {
                                log("Bilibili category title pretranslation failed aid=" + aid
                                        + " title=" + title, t);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(categoryAv, "g", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object titleObject = param.getResult();
                    if (!(titleObject instanceof String)) {
                        return;
                    }

                    String title = (String) titleObject;
                    if (isEmpty(title) || !containsCjk(title)) {
                        return;
                    }

                    long aid = getLongByMethod(param.thisObject, "a", 0L);
                    String key = aid + "|" + title;
                    String cached = bilibiliCategoryTitleCache.get(key);
                    if (isEmpty(cached) || BILIBILI_TRANSLATION_PENDING.equals(cached)) {
                        cached = bilibiliCategoryTitleTextCache.get(title);
                    }
                    if (!isEmpty(cached) && !BILIBILI_TRANSLATION_PENDING.equals(cached)) {
                        param.setResult(cached);
                        return;
                    }

                    if (cached == null) {
                        try {
                            String translated = requestBilibiliTitleTranslation(classLoader, aid, title);
                            if (!isEmpty(translated) && !translated.equals(title)) {
                                bilibiliCategoryTitleCache.put(key, translated);
                                bilibiliCategoryTitleTextCache.put(title, translated);
                                XposedHelpers.setObjectField(param.thisObject, "i", translated);
                                param.setResult(translated);
                                log("Bilibili category title rendered aid=" + aid
                                        + " title=" + title + " -> " + translated);
                                return;
                            }
                        } catch (Throwable t) {
                            log("Bilibili category title render translation failed aid=" + aid
                                    + " title=" + title, t);
                        }

                        bilibiliCategoryTitleCache.put(key, BILIBILI_TRANSLATION_PENDING);
                        translateBilibiliCategoryTitleAsync(classLoader, param.thisObject, key, aid, title);
                    }
                }
            });
            log("hooked Bilibili category title translation");
        } catch (Throwable t) {
            log("Bilibili category title translation hook failed", t);
        }
    }

    private static void hookBilibiliAuthorSpaceTitleTranslation(ClassLoader classLoader) {
        try {
            Class<?> spaceVideo = XposedHelpers.findClass(
                    "com.bilibili.app.authorspace.api.BiliSpaceVideo", classLoader);
            XposedHelpers.findAndHookMethod(spaceVideo, "displayedTitle", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String title = getStringField(param.thisObject, "title");
                    if (isEmpty(title) || !containsCjk(title)) {
                        return;
                    }

                    String businessId = getStringField(param.thisObject, "param");
                    if (isEmpty(businessId)) {
                        log("Bilibili author title skip: missing businessId title=" + title);
                        return;
                    }

                    String key = businessId + "|" + title;
                    String cached = bilibiliAuthorTitleCache.get(key);
                    if (isEmpty(cached) || BILIBILI_TRANSLATION_PENDING.equals(cached)) {
                        cached = bilibiliAuthorTitleTextCache.get(title);
                    }
                    if (!isEmpty(cached) && !BILIBILI_TRANSLATION_PENDING.equals(cached)) {
                        applyBilibiliAuthorTitleTranslation(param.thisObject, cached);
                        param.setResult(cached);
                        return;
                    }

                    if (cached == null) {
                        try {
                            String translated = requestBilibiliTitleTranslation(
                                    classLoader, businessId, title, "main.space.0.0");
                            if (!isEmpty(translated) && !translated.equals(title)) {
                                bilibiliAuthorTitleCache.put(key, translated);
                                bilibiliAuthorTitleTextCache.put(title, translated);
                                applyBilibiliAuthorTitleTranslation(param.thisObject, translated);
                                param.setResult(translated);
                                log("Bilibili author title translated aid=" + businessId
                                        + " title=" + title + " -> " + translated);
                                return;
                            }
                        } catch (Throwable t) {
                            log("Bilibili author title translation failed aid=" + businessId
                                    + " title=" + title, t);
                        }

                        bilibiliAuthorTitleCache.put(key, BILIBILI_TRANSLATION_PENDING);
                        translateBilibiliAuthorTitleAsync(classLoader, param.thisObject, key, businessId, title);
                    }
                }
            });
            log("hooked Bilibili author space title translation");
        } catch (Throwable t) {
            log("Bilibili author space title translation hook failed", t);
        }
    }

    private static void translateBilibiliCategoryTitleAsync(
            ClassLoader classLoader, Object card, String key, long aid, String title) {
        new Thread(() -> {
            try {
                String translated = requestBilibiliTitleTranslation(classLoader, aid, title);
                if (isEmpty(translated) || translated.equals(title)) {
                    bilibiliCategoryTitleCache.remove(key);
                    log("Bilibili category title translation empty aid=" + aid + " title=" + title);
                    return;
                }

                bilibiliCategoryTitleCache.put(key, translated);
                bilibiliCategoryTitleTextCache.put(title, translated);
                if (card != null) {
                    XposedHelpers.setObjectField(card, "i", translated);
                }
                log("Bilibili category title translated aid=" + aid
                        + " title=" + title + " -> " + translated);
            } catch (Throwable t) {
                bilibiliCategoryTitleCache.remove(key);
                log("Bilibili category title translation failed aid=" + aid + " title=" + title, t);
            }
        }, "bili-category-title-translate").start();
    }

    private static void translateBilibiliAuthorTitleAsync(
            ClassLoader classLoader, Object video, String key, String businessId, String title) {
        new Thread(() -> {
            try {
                String translated = requestBilibiliTitleTranslation(
                        classLoader, businessId, title, "main.space.0.0");
                if (isEmpty(translated) || translated.equals(title)) {
                    bilibiliAuthorTitleCache.remove(key);
                    log("Bilibili author title translation empty aid=" + businessId + " title=" + title);
                    return;
                }

                bilibiliAuthorTitleCache.put(key, translated);
                bilibiliAuthorTitleTextCache.put(title, translated);
                applyBilibiliAuthorTitleTranslation(video, translated);
                log("Bilibili author title translated async aid=" + businessId
                        + " title=" + title + " -> " + translated);
            } catch (Throwable t) {
                bilibiliAuthorTitleCache.remove(key);
                log("Bilibili author title translation async failed aid=" + businessId
                        + " title=" + title, t);
            }
        }, "bili-author-title-translate").start();
    }

    private static void hookBilibiliActivityRefresh() {
        if (bilibiliActivityHooked) {
            return;
        }
        bilibiliActivityHooked = true;

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (BILIBILI_PACKAGE.equals(activity.getPackageName())) {
                        bilibiliActivity = activity;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject == bilibiliActivity) {
                        bilibiliActivity = null;
                    }
                }
            });
            log("hooked Bilibili activity refresh");
        } catch (Throwable t) {
            log("Bilibili activity refresh hook failed", t);
        }
    }

    private static void scheduleBilibiliActivityRefresh() {
        Activity activity = bilibiliActivity;
        if (activity == null || activity.isFinishing() || bilibiliActivityRefreshScheduled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBilibiliActivityRefreshMs < 120000L) {
            return;
        }

        bilibiliActivityRefreshScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            bilibiliActivityRefreshScheduled = false;
            Activity current = bilibiliActivity;
            if (current == null || current.isFinishing()) {
                return;
            }
            try {
                lastBilibiliActivityRefreshMs = System.currentTimeMillis();
                current.recreate();
                log("Bilibili activity refreshed for translated category titles");
            } catch (Throwable t) {
                log("Bilibili activity refresh failed", t);
            }
        }, 700L);
    }

    private static String requestBilibiliTitleTranslation(ClassLoader classLoader, long aid, String title)
            throws Throwable {
        return requestBilibiliTitleTranslation(classLoader, String.valueOf(aid), title, "tm.category.0.0");
    }

    private static String requestBilibiliTitleTranslation(
            ClassLoader classLoader, String businessId, String title, String spmid) throws Throwable {
        Class<?> simpleReq = XposedHelpers.findClass(
                "com.bapis.bilibili.app.translation.v1.TranslationSimpleReq", classLoader);
        Class<?> translationBusiness = XposedHelpers.findClass(
                "com.bapis.bilibili.app.translation.v1.TranslationBusiness", classLoader);
        Class<?> translationMoss = XposedHelpers.findClass(
                "com.bapis.bilibili.app.translation.v1.TranslationMoss", classLoader);

        Object builder = XposedHelpers.callStaticMethod(simpleReq, "newBuilder");
        Object arcBusiness = XposedHelpers.getStaticObjectField(translationBusiness, "TRANS_BIZ_ARC");
        XposedHelpers.callMethod(builder, "setBizType", arcBusiness);
        XposedHelpers.callMethod(builder, "setBusinessId", businessId);
        XposedHelpers.callMethod(builder, "addFields", "title");
        XposedHelpers.callMethod(builder, "addText", title);
        XposedHelpers.callMethod(builder, "setSpmid", spmid);

        Object req = XposedHelpers.callMethod(builder, "build");
        Object moss = XposedHelpers.newInstance(translationMoss);
        Object reply = XposedHelpers.callMethod(moss, "executeTranslationSimple", req);
        int count = (Integer) XposedHelpers.callMethod(reply, "getResultsCount");
        if (count <= 0) {
            return null;
        }
        Object result = XposedHelpers.callMethod(reply, "getResults", 0);
        return (String) XposedHelpers.callMethod(result, "getTranslatedText");
    }

    private static void applyBilibiliAuthorTitleTranslation(Object video, String translated) {
        try {
            XposedHelpers.setObjectField(video, "translatedTitle", translated);
            XposedHelpers.setObjectField(video, "translateStatus", "translated");
        } catch (Throwable t) {
            log("Bilibili author title field patch failed", t);
        }
    }

    private static void forceBilibiliPreferredSubtitleLanguage(Object req) {
        if (req == null) {
            return;
        }

        try {
            String preferred = (String) XposedHelpers.callMethod(req, "getPreferredLanguage");
            if (isEmpty(preferred)) {
                XposedHelpers.setObjectField(req, "preferredLanguage_", "pt");
            }
        } catch (Throwable t) {
            log("Bilibili subtitle preferred language patch failed", t);
        }
    }

    private static String describeBilibiliSubtitleRequest(Object req) {
        if (req == null) {
            return "null";
        }

        return "pid=" + safeCall(req, "getPid")
                + " oid=" + safeCall(req, "getOid")
                + " type=" + safeCall(req, "getType")
                + " curLang=" + safeCall(req, "getCurLanguage")
                + " curProd=" + safeCall(req, "getCurProductionTypeValue")
                + " preferred=" + safeCall(req, "getPreferredLanguage")
                + " preferred2=" + safeCall(req, "getPreferred2Language");
    }

    private static String describeBilibiliSubtitleReply(Object reply) {
        if (reply == null) {
            return "null";
        }

        try {
            Object subtitle = XposedHelpers.callMethod(reply, "getSubtitle");
            int count = (Integer) XposedHelpers.callMethod(subtitle, "getSubtitlesCount");
            StringBuilder builder = new StringBuilder();
            builder.append("lan=").append(safeCall(subtitle, "getLan"))
                    .append(" lanDoc=").append(safeCall(subtitle, "getLanDoc"))
                    .append(" count=").append(count);

            int limit = Math.min(count, 5);
            for (int i = 0; i < limit; i++) {
                Object item = XposedHelpers.callMethod(subtitle, "getSubtitles", i);
                builder.append(" [")
                        .append("lan=").append(safeCall(item, "getLan"))
                        .append(", doc=").append(safeCall(item, "getLanDoc"))
                        .append(", type=").append(safeCall(item, "getTypeValue"))
                        .append(", aiType=").append(safeCall(item, "getAiTypeValue"))
                        .append(", aiStatus=").append(safeCall(item, "getAiStatusValue"))
                        .append(", url=").append(!isEmpty((String) safeCall(item, "getSubtitleUrl")))
                        .append("]");
            }
            return builder.toString();
        } catch (Throwable t) {
            return "describe failed: " + t;
        }
    }

    private static String describeBilibiliTranslationRequest(Object req) {
        if (req == null) {
            return "null";
        }

        return "biz=" + safeCall(req, "getBizType")
                + " bizId=" + safeCall(req, "getBusinessId")
                + " spmid=" + safeCall(req, "getSpmid")
                + " fields=" + safeCall(req, "getFieldsList")
                + " textCount=" + safeCall(req, "getTextCount")
                + " text0=" + firstListItem(req, "getText", "getTextCount");
    }

    private static String describeBilibiliTranslationReply(Object reply) {
        if (reply == null) {
            return "null";
        }

        try {
            int count = (Integer) XposedHelpers.callMethod(reply, "getResultsCount");
            StringBuilder builder = new StringBuilder();
            builder.append("count=").append(count);
            int limit = Math.min(count, 3);
            for (int i = 0; i < limit; i++) {
                Object item = XposedHelpers.callMethod(reply, "getResults", i);
                builder.append(" [text=").append(safeCall(item, "getTranslatedText")).append("]");
            }
            return builder.toString();
        } catch (Throwable t) {
            return "describe failed: " + t;
        }
    }

    private static Object firstListItem(Object receiver, String getMethod, String countMethod) {
        try {
            int count = (Integer) XposedHelpers.callMethod(receiver, countMethod);
            if (count <= 0) {
                return "";
            }
            return XposedHelpers.callMethod(receiver, getMethod, 0);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static Object safeCall(Object receiver, String methodName) {
        try {
            return XposedHelpers.callMethod(receiver, methodName);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String getStringField(Object receiver, String fieldName) {
        try {
            Object value = XposedHelpers.getObjectField(receiver, fieldName);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static boolean containsCjk(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u3400' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff')) {
                return true;
            }
        }
        return false;
    }

    private static long getLongByMethod(Object receiver, String methodName, long fallback) {
        try {
            Object value = XposedHelpers.callMethod(receiver, methodName);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
            // Keep the fallback key when the model shape differs.
        }
        return fallback;
    }

    private static List<?> ensureBilibiliChannelTab(ClassLoader classLoader, List<?> tabs) throws Throwable {
        List<Object> patched = tabs == null ? new ArrayList<>() : new ArrayList<>(tabs);
        for (Object tab : patched) {
            if (tab != null && isBilibiliChannelUri((String) XposedHelpers.getObjectField(tab, "uri"))) {
                return tabs;
            }
        }

        Class<?> tabClass = XposedHelpers.findClass(
                "tv.danmaku.bili.ui.main2.resource.MainResourceManager$Tab", classLoader);
        Object tab = XposedHelpers.newInstance(tabClass);
        XposedHelpers.setObjectField(tab, "tabId", "201");
        XposedHelpers.setObjectField(tab, "name", "Categorias");
        XposedHelpers.setObjectField(tab, "reportId", "channel_tab");
        XposedHelpers.setObjectField(tab, "uri", "bilibili://main/top_category");
        XposedHelpers.setIntField(tab, "pos", patched.size() + 1);
        XposedHelpers.setIntField(tab, "type", 0);
        XposedHelpers.setIntField(tab, "selected", 0);
        XposedHelpers.setBooleanField(tab, "showRedDotOnTab", false);
        patched.add(tab);
        log("patched Bilibili top_more input with channel tab");
        return patched;
    }

    private static Object newBilibiliMoreEntry(ClassLoader classLoader) throws Throwable {
        Class<?> entryClass = XposedHelpers.findClass(
                "tv.danmaku.bili.ui.main2.resource.x", classLoader);
        return XposedHelpers.newInstance(entryClass, "bilibili://main/top_category", "");
    }

    private static boolean hasBilibiliChannelEntry(List<?> entries) {
        for (Object entry : entries) {
            if (entry == null) {
                continue;
            }
            try {
                String uri = (String) XposedHelpers.getObjectField(entry, "uri");
                if (isBilibiliChannelUri(uri)) {
                    return true;
                }
            } catch (Throwable ignored) {
                try {
                    String uri = (String) XposedHelpers.getObjectField(entry, "a");
                    if (isBilibiliChannelUri(uri)) {
                        return true;
                    }
                } catch (Throwable ignoredAgain) {
                    // Entry shape differs; keep scanning.
                }
            }
        }
        return false;
    }

    private static boolean isBilibiliChannelUri(String uri) {
        return uri != null
                && (uri.startsWith("bilibili://pegasus/channel")
                || uri.startsWith("bilibili://main/top_category"));
    }

    private static void hookBilibiliRegionSetter(Class<?> regionManager, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(regionManager, methodName, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String region = (String) param.args[0];
                    if (!"CN".equals(region)) {
                        param.args[0] = "CN";
                        log("normalized Bilibili " + methodName + " from " + region + " to CN");
                    }
                }
            });
        } catch (Throwable t) {
            log("Bilibili " + methodName + " hook failed", t);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(String message, Throwable t) {
        XposedBridge.log(TAG + ": " + message + ": " + t);
    }
}
