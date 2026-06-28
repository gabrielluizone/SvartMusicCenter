package com.matejdro.wearmusiccenter

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.matejdro.wearmusiccenter.di.DaggerAppComponent
import com.matejdro.wearmusiccenter.logging.CrashlyticsExceptionWearHandler
import com.matejdro.wearmusiccenter.logging.TimberCrashlytics
import com.matejdro.wearutils.logging.FileLogger
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import pl.tajchert.exceptionwear.ExceptionDataListenerService
import timber.log.Timber
import javax.inject.Inject


class WearMusicCenter : Application(), HasAndroidInjector {
    @Inject
    lateinit var androidInjector: DispatchingAndroidInjector<Any>

    override fun onCreate() {
        DaggerAppComponent.builder()
                .application(this)
                .build()
                .inject(this)

        super.onCreate()

        Timber.setAppTag("WearMusicCenter")

        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Timber.plant(Timber.AndroidDebugTree(isDebuggable))

        if (!isDebuggable) {
            Timber.plant(TimberCrashlytics())
            ExceptionDataListenerService.setHandler(CrashlyticsExceptionWearHandler())
        }

        val fileLogger = FileLogger.getInstance(this)
        fileLogger.activate()
        Timber.plant(fileLogger)

        applyThemeFromPreferences()
    }

    private fun applyThemeFromPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = when (prefs.getString("app_theme", "system")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    override fun androidInjector(): AndroidInjector<Any> = androidInjector
}
