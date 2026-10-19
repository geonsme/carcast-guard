package cn.luohui.mpguard;

/** 版本号归一化与比较；纯 Java 实现，便于脱离 Android 校验。 */
final class VersionUtil {

    private VersionUtil() {}

    static String normalize(String tag) {
        if (tag == null) return "";
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int end = 0;
        while (end < s.length()) {
            char c = s.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.') end++;
            else break;
        }
        s = s.substring(0, end);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 返回 >0 表示 left 更新，0 表示相同，<0 表示 left 更旧。 */
    static int compare(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        String[] pa = a.isEmpty() ? new String[0] : a.split("\\.");
        String[] pb = b.isEmpty() ? new String[0] : b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? num(pa[i]) : 0;
            int y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int num(String s) {
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) v;
    }
}
