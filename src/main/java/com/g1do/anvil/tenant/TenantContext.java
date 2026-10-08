package com.g1do.anvil.tenant;

/**
 * Holds the authenticated tenant for the current request.
 * Populated by {@link TenantAuthFilter} from the {@code X-Tenant-Id} header
 * after lookup in {@code tenants.id}.
 */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        CURRENT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
