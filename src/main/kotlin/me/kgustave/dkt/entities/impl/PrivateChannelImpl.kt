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
package me.kgustave.dkt.entities.impl

import me.kgustave.dkt.entities.*
import me.kgustave.dkt.requests.Route
import me.kgustave.dkt.requests.promises.MessagePromise

/**
 * @author Kaidan Gustave
 */
class PrivateChannelImpl
constructor(override val api: APIImpl, override val id: Long, override val recipient: UserImpl): PrivateChannel {
    override val guild: Guild? = null
    override val type: Channel.Type = Channel.Type.PRIVATE

    init {
        recipient.internalPrivateChannel = this
    }

    override fun send(text: String): MessagePromise {
        return MessagePromise(this, api, Route.CreateMessage.format(id), text)
    }

    override fun send(embed: Embed): MessagePromise {
        TODO("Sending embeds is not implemented yet")
    }

    override fun send(message: Message): MessagePromise {
        TODO("not implemented")
    }

    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean = other is PrivateChannel && Snowflake.equals(this, other)
    override fun toString(): String = Snowflake.toString("PrivateChannel", this)
}