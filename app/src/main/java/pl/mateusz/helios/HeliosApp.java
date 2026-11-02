package pl.mateusz.helios;

import android.app.Application;

public final class HeliosApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        I18n.init(this);
    }
}
