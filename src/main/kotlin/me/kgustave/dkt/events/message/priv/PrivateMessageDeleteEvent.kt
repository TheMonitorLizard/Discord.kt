/*
 * Copyright 2017 Kaidan Gustave
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
package me.kgustave.dkt.events.message.priv

import me.kgustave.dkt.API
import me.kgustave.dkt.entities.Guild
import me.kgustave.dkt.entities.PrivateChannel
import me.kgustave.dkt.entities.TextChannel
import me.kgustave.dkt.events.message.MessageDeleteEvent

/**
 * @author Kaidan Gustave
 */
class PrivateMessageDeleteEvent(
    override val api: API,
    override val responseNumber: Long,
    override val messageId: Long,
    override val channel: PrivateChannel
): MessageDeleteEvent {
    override val textChannel: TextChannel?
        get() = null
    override val guild: Guild?
        get() = null

    override val privateChannel: PrivateChannel
        get() = channel
}
