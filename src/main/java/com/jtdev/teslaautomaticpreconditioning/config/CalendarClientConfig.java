package com.jtdev.teslaautomaticpreconditioning.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;


@Configuration
public class CalendarClientConfig {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    @Value("${google.calendar.credentials-path}")
    private Resource credentialsPath;
    @Value("${google.calendar.application-name:Tesla Automatic Preconditioning}")
    private String applicationName;
    /**
     * Optional: email of the user to impersonate when using a service account with
     * domain-wide delegation. Leave blank if not applicable.
     */
    @Value("${google.calendar.impersonate:}")
    private String impersonatedUser;

    @Value("${google.weather.api-key:}")
    private String weatherApiKey;

    @Value("${google.weather.base-url:https://weather.googleapis.com/v1}")
    private String weatherBaseUrl;

    @Bean
    public Calendar googleCalendarClient() throws GeneralSecurityException, IOException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        // Scopes you need; add/remove based on your use cases.
        List<String> scopes = new ArrayList<>();
        scopes.add(CalendarScopes.CALENDAR_READONLY);
        scopes.add(CalendarScopes.CALENDAR_EVENTS);

        GoogleCredentials baseCreds = GoogleCredentials
                .fromStream(credentialsPath.getInputStream())
                .createScoped(scopes);

        GoogleCredentials effectiveCreds = baseCreds;
        // If we have domain-wide delegation with a service account
        if (!impersonatedUser.isBlank() && baseCreds instanceof ServiceAccountCredentials sac) {
            effectiveCreds = sac.createDelegated(impersonatedUser);
        }

        HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(effectiveCreds);

        return new Calendar.Builder(httpTransport, JSON_FACTORY, requestInitializer)
                .setApplicationName(applicationName)
                .build();
    }

    @Bean
    public WebClient weatherWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(weatherBaseUrl)
                .filter((request, next) -> {
                    URI original = request.url();
                    String query = original.getQuery();
                    boolean hasKey = query != null && query.contains("key=");
                    URI withKey = hasKey ? original :
                            UriComponentsBuilder.fromUri(original)
                                    .queryParam("key", weatherApiKey)
                                    .build(true)
                                    .toUri();
                    ClientRequest newReq = hasKey ? request : ClientRequest.from(request).url(withKey).build();
                    return next.exchange(newReq);
                })
                .build();
    }
}
