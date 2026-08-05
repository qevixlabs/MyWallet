package com.mywallet.di

import com.mywallet.data.repo.Clock
import com.mywallet.data.repo.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
