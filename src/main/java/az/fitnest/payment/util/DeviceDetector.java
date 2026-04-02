package az.fitnest.payment.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class DeviceDetector {

    public static String detectDeviceType() {
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return "UNKNOWN";
        }

        String platformHeader = request.getHeader("X-Platform");
        if (platformHeader != null && !platformHeader.trim().isEmpty()) {
            if ("IOS".equalsIgnoreCase(platformHeader.trim())) {
                return "iOS";
            } else if ("ANDROID".equalsIgnoreCase(platformHeader.trim())) {
                return "Android";
            }
        }

        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.trim().isEmpty()) {
            return "UNKNOWN";
        }

        String ua = userAgent.toLowerCase();

        if (ua.contains("android") || ua.contains("dalvik")) {
            return "Android";
        }

        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")
            || ua.contains("ios") || ua.contains("cfnetwork") || ua.contains("darwin")) {
            return "iOS";
        }

        return "UNKNOWN";
    }

    private static HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }
}
