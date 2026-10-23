package com.cizeylem.bir;

import android.app.Application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Genel API'nin Java'dan çağrılabilirliğini DERLEME ZAMANINDA sınar (SPEC §1): varsayılan değerli
 * her Kotlin parametresinin @JvmOverloads ile Java tarafında kısa bir imzası olmalı, tüm giriş
 * noktaları @JvmStatic olmalı. Çalıştırılması gerekmez; derlenmesi testin kendisidir.
 *
 * Üçüncü taraf test kütüphanesi kullanılmaz: çekirdek modülün androidTest kaynak kümesine
 * bağımlılık eklemeden yalnız javac ile doğrulanır.
 */
public final class JavaApiTest {

    private JavaApiTest() {
    }

    public static void configurationShortAndLongForm(Application app) {
        // Tek argümanlı kısa biçim (@JvmOverloads).
        Cizeylem.initialize(app, new Configuration("ck_w_test"));

        Logger logger = new Logger() {
            @Override
            public void log(String message) {
                System.out.println(message);
            }
        };
        Cizeylem.initialize(app, new Configuration(
                "ck_w_test", "https://in.cizeylem.com", null, 30, 30000L, 1000, true, logger));
    }

    public static void publicSurface() {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("bolum", "42");

        Cizeylem.track("cz_test");
        Cizeylem.track("cz_test", props);

        Cizeylem.identify("kullanici-1");
        Cizeylem.identify("kullanici-1", props);

        Cizeylem.setUserProperties(props);
        Cizeylem.setUserPropertiesOnce(props);

        Map<String, Number> counters = new HashMap<String, Number>();
        counters.put("dinleme", 1);
        Cizeylem.addUserProperties(counters);

        List<String> keys = new ArrayList<String>();
        keys.add("eski");
        Cizeylem.unsetUserProperties(keys);

        Cizeylem.sendUserProperties();
        Cizeylem.sendUserProperties("kullanici-1");

        Cizeylem.setConsent(new Consent(true));
        Cizeylem.setConsent(new Consent(true, true, false));
        Cizeylem.setOptOut(true);

        Cizeylem.handleDeepLink("myapp://open?utm_source=meta");
        Cizeylem.flush();
        Cizeylem.reset();

        String distinctId = Cizeylem.getDistinctId();
        Stats stats = Cizeylem.stats();
        int queued = stats.getQueued();
        int dropped = stats.getDropped();
        int rejected = stats.getRejected();
        boolean disabled = stats.getDisabled();
        System.out.println(distinctId + queued + dropped + rejected + disabled);
    }
}
