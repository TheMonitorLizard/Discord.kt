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

import me.kgustave.dkt.API
import me.kgustave.dkt.entities.Guild
import me.kgustave.dkt.entities.GuildChannel
import me.kgustave.dkt.entities.PermissionOverride

/**
 * @author Kaidan Gustave
 */
abstract class AbstractPermissionOverrideImpl(
    override val api: API,
    override val channel: GuildChannel,
    protected val id: Long // Used for debugging mostly
): PermissionOverride {
    override val guild: Guild get() = channel.guild
    override var allowedRaw: Long = 0L
    override var deniedRaw: Long = 0L
}
