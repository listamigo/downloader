package com.elimd.downloader.core.di

import javax.inject.Qualifier

/** Resolutor local basado en NewPipeExtractor. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalResolver

/** Resolutor remoto contra el backend propio con yt-dlp. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RemoteResolver
