package com.hmdp.utils;
public final class ShopGeoPolicy {
    private ShopGeoPolicy() {}
    public static final int PAGE_SIZE = 5;
    public static final int MAX_PAGE = 20;
    public static final int MAX_CANDIDATES = 101;
    public static final int DEFAULT_RADIUS = 5000;
    public static final int MAX_RADIUS = 20000;
    public static final int MAX_TYPES = 1000;
    public static boolean validCoordinates(Double x, Double y) {
        return x != null && y != null && Double.isFinite(x) && Double.isFinite(y)
                && x >= -180 && x <= 180 && y >= -85.05112878 && y <= 85.05112878;
    }
}
