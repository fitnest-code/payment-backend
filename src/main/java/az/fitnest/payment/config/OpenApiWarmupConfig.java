package az.fitnest.payment.config;

import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

@Configuration
public class OpenApiWarmupConfig {

    private final SpringDocConfigProperties springDocConfigProperties;
    private final OpenApiWebMvcResource openApiResource;

    @Value("${server.port:8080}")
    private int serverPort;

    @Autowired
    public OpenApiWarmupConfig(
            SpringDocConfigProperties springDocConfigProperties,
            @Autowired(required = false) OpenApiWebMvcResource openApiResource) {
        this.springDocConfigProperties = springDocConfigProperties;
        this.openApiResource = openApiResource;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmUpOpenApi() {
        if (!springDocConfigProperties.getApiDocs().isEnabled()) {
            return;
        }

        try {
            // Give Tomcat a moment to fully initialize its connectors
            Thread.sleep(2000);
            if (openApiResource != null) {
                try {
                    openApiResource.openapiJson(null, "", Locale.getDefault());
                    return;
                } catch (Exception e) {
                }
            }

            warmupViaHttp();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void warmupViaHttp() {
        try {
            String apiDocsPath = springDocConfigProperties.getApiDocs().getPath();
            if (apiDocsPath == null || apiDocsPath.isEmpty()) {
                apiDocsPath = "/v3/api-docs";
            }

            URL url = new URL("http://localhost:" + serverPort + apiDocsPath);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(30000);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                connection.getInputStream().readAllBytes();
            }

            connection.disconnect();
        } catch (Exception e) {
        }
    }
}
