/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bskyviewer.indexer

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import kotlin.concurrent.Volatile

private val logger = KotlinLogging.logger {}

@Component
class LifecycleLogger : SmartLifecycle {
    @Volatile
    private var running = true

    override fun start() {
        this.running = true
    }

    override fun isRunning(): Boolean {
        return this.running
    }

    override fun stop() {
        logger.info (Exception()) { "server stop requested at" }
        this.running = false
    }
}
