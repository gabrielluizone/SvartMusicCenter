package com.matejdro.wearmusiccenter.view.mainactivity

import androidx.lifecycle.ViewModel
import com.matejdro.wearmusiccenter.config.WatchInfoProvider
import com.matejdro.wearmusiccenter.music.ActiveMediaSessionProvider
import javax.inject.Inject

class MainActivityViewModel @Inject constructor(
    val watchInfoProvider: WatchInfoProvider,
    val activeMediaSessionProvider: ActiveMediaSessionProvider
) : ViewModel()
