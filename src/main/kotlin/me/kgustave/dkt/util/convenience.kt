/*
 * Copyright 2018 Kaidan Gustave
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.kgustave.dkt.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.reflect.KClass

/**
 * The [current system time][System.currentTimeMillis] in milliseconds.
 */
inline val currentTime: Long
    inline get() = System.currentTimeMillis()

fun emptyIntArray() = IntArray(0)

fun createLogger(klazz: KClass<*>): Logger = LoggerFactory.getLogger(klazz.java)

fun createLogger(name: String): Logger = LoggerFactory.getLogger(name)

fun <T> unmodifiableList(list: List<T>): List<T> = Collections.unmodifiableList(list)

fun <T> unmodifiableList(vararg elements: T): List<T> = Arrays.asList(*elements)

fun <T> singletonList(value: T): List<T> = Collections.singletonList(value)
