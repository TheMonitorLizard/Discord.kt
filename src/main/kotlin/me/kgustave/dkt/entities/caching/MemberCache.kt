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
package me.kgustave.dkt.entities.caching

import me.kgustave.dkt.entities.Member
import me.kgustave.dkt.entities.Role

/**
 * @author Kaidan Gustave
 */
interface MemberCache : EntityCache<Member> {
    fun getById(id: Long): Member?
    fun getByNickname(name: String, ignoreCase: Boolean = false): List<Member>
    fun getByUsername(name: String, ignoreCase: Boolean = false): List<Member>
    fun getWithRoles(vararg roles: Role): List<Member>
    fun getWithRoles(roles: Collection<Role>): List<Member>

    operator fun get(id: Long): Member?
}
