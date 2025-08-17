package com.jtdev.teslaautomaticpreconditioning.config;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.rpc.HeaderProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.maps.routing.v2.RoutesClient;
import com.google.maps.routing.v2.RoutesSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class GoogleMapsConfig {

    /**
     * Authentication mode:
     * - "adc" (default): use Application Default Credentials (service account or user creds).
     * - "api-key": send X-Goog-Api-Key header and disable OAuth.
     */
    @Value("${google.maps.routes.auth:adc}")
    private String authMode;

    @Value("${google.creds.absolute.path:/Users/joshuaterk/IdeaProjects/Tesla-Automatic-Preconditioning/src/main/resources/google/service-account.json}")
    private String credsPath;

    /**
     * Only required when auth=api-key
     */
    @Value("${google.maps.api-key:}")
    private String apiKey;

    /**
     * Optional: override endpoint, e.g. "routes.googleapis.com:443"
     */
    @Value("${google.maps.routes.endpoint:}")
    private String endpoint;

    /**
     * Field mask header. Use "*" for everything, or a minimal mask for better performance,
     * e.g. "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline"
     */
    @Value("${google.maps.routes.field-mask:*}")
    private String fieldMask;

    /**
     * Optional: bill/quota against a specific GCP project
     */
    @Value("${google.maps.routes.quota-project-id:}")
    private String quotaProjectId;

    @Bean(destroyMethod = "close")
    public RoutesClient routesClient() throws IOException {
        RoutesSettings.Builder builder = RoutesSettings.newBuilder();

        if (endpoint != null && !endpoint.isBlank()) {
            builder.setEndpoint(endpoint);
        }

        HeaderProvider headerProvider = () -> {
            Map<String, String> headers = new HashMap<>();
            if (fieldMask != null && !fieldMask.isBlank()) {
                headers.put("X-Goog-FieldMask", fieldMask);
            }
            if ("api-key".equalsIgnoreCase(authMode)) {
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException("google.maps.routes.auth=api-key but google.maps.api-key is empty");
                }
                headers.put("X-Goog-Api-Key", apiKey);
            }
            if (quotaProjectId != null && !quotaProjectId.isBlank()) {
                headers.put("X-Goog-User-Project", quotaProjectId);
            }
            return headers;
        };
        builder.setHeaderProvider(headerProvider);

        if ("api-key".equalsIgnoreCase(authMode)) {
            builder.setCredentialsProvider(NoCredentialsProvider.create());
        } else {
            GoogleCredentials adc = GoogleCredentials.fromStream(new FileInputStream(credsPath));
            builder.setCredentialsProvider(FixedCredentialsProvider.create(adc));
        }

        return RoutesClient.create(builder.build());
    }
}