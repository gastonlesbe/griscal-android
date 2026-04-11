package app.griscal.notif;

import android.app.Application;

public class GriscalApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppCheckHelper.install();
    }
}
